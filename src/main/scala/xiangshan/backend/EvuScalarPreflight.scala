package xiangshan.backend

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import utility.XSPerfAccumulate
import xiangshan.{ExternalVectorKey, ExceptionNO, Redirect}
import xiangshan.backend.Bundles.ExuOutput
import xiangshan.backend.rob.{EvuLoadQueue, RobPtr}
import xiangshan.evu._

/** Connects the shared scalar writeback lane to one non-speculative address checker. */
object EvuScalarPreflight {
  def connect(source: DecoupledIO[ExuOutput], sink: DecoupledIO[ExuOutput], check: EvuRangeIO,
      csr: EvuCSRCoreIO, reserve: ValidIO[RobPtr], robHead: RobPtr, redirect: ValidIO[Redirect],
      barrier: EvuPreflightReasons, inReset: Bool)(implicit p: Parameters): (Bool, Bool) = {
    val ep = p(ExternalVectorKey)
    val incoming = source.bits.amuCtrl.get.data.asTypeOf(new EvuCoreCommand(ep))
    val incomingOp = incoming.instr(31, 25)
    val isEvu = source.bits.amuCtrl.get.evu
    val bufferLoad = isEvu && incomingOp === EvuIsa.load
    val incomingSew = Mux(csr.configured, csr.config.vtype(5, 3), incoming.instr(14, 12))
    val incomingLength = Mux(EvuIsa.isElementZero(incomingOp),
      Mux(csr.configured && csr.config.vl === 0.U, 0.U, 4.U),
      Mux(csr.configured, csr.config.vl << incomingSew, incoming.length))
    val configOp = incomingOp === EvuIsa.setvli || incomingOp === EvuIsa.setvl
    val control = configOp || incomingOp === EvuIsa.fence
    val illegalConfig = !control && (csr.config.vill ||
      !EvuIsa.supportsElementWidth(incomingOp.pad(8), incomingSew))
    val effectiveLmul = Mux(csr.configured, csr.config.vtype(2, 0), 0.U)
    val invalidGroup = !EvuIsa.supportsRegisterGroups(incoming.instr, effectiveLmul)
    val permissionFault = !csr.permitted || illegalConfig || invalidGroup
    val killed = source.bits.robIdx.needFlush(redirect)
    val direct = source.valid && !bufferLoad && !killed
    val directMemory = isEvu && (EvuIsa.isLoad(incomingOp) || EvuIsa.isStore(incomingOp)) && !permissionFault
    // The slots contain no page maps. That wide field is supplied only at
    // writeback by the single existing checker.
    val desc = Wire(new Bundle {
      val wb = chiselTypeOf(source.bits)
      val cmd = new EvuCoreCommandData(ep)
      val bytes = UInt(32.W)
      val sew = UInt(3.W)
      val illegal = Bool()
    })
    desc.wb := 0.U.asTypeOf(source.bits)
    desc.wb.robIdx := source.bits.robIdx
    desc.wb.debug := source.bits.debug
    desc.wb.debugInfo := source.bits.debugInfo
    desc.wb.debug_seqNum := source.bits.debug_seqNum
    desc.wb.amuCtrl.get := source.bits.amuCtrl.get
    desc.wb.amuCtrl.get.data := 0.U
    desc.cmd := EvuCoreCommandData.compact(incoming, ep)
    desc.cmd.config := csr.config
    desc.bytes := incomingLength
    desc.sew := incomingSew
    desc.illegal := permissionFault
    val slots = Module(new EvuLoadQueue(chiselTypeOf(desc), (new RobPtr).getWidth))
    slots.io.reserve.valid := reserve.valid
    slots.io.reserve.bits := reserve.bits.asUInt
    slots.io.head := robHead.asUInt
    for (i <- 0 until 2) {
      slots.io.kill(i) := slots.io.tags(i).asTypeOf(new RobPtr).needFlush(redirect)
    }
    slots.io.capture.valid := source.valid && bufferLoad && !killed
    slots.io.capture.bits.tag := source.bits.robIdx.asUInt
    slots.io.capture.bits.data := desc
    val q = slots.io.retire
    val queued = q.valid
    val invalidQueued = q.bits.illegal || !csr.permitted
    val payload = Mux(queued, q.bits.cmd, EvuCoreCommandData.compact(incoming, ep))
    val effectiveLength = Mux(queued, q.bits.bytes, incomingLength)
    val effectiveSew = Mux(queued, q.bits.sew, incomingSew)
    val f7 = payload.instr(31, 25)
    val load = EvuIsa.isLoad(f7)
    val store = EvuIsa.isStore(f7)
    val tag = Mux(queued, q.bits.wb.robIdx, source.bits.robIdx).asUInt
    val memory = Mux(queued, !invalidQueued, direct && directMemory)
    val checking = RegInit(false.B)
    val stable = csr.preflightConfigReady && csr.permitted &&
      (!queued || q.bits.cmd.config.asUInt === csr.config.asUInt)
    val generalBarrier = barrier.control || barrier.status
    val preflightBlocked = Mux(load, EvuPreflightReasons.blocksLoad(barrier, stable), generalBarrier)
    check.req.valid := memory && !checking && !preflightBlocked && !redirect.valid
    val stalled = memory && !checking && preflightBlocked && !redirect.valid
    XSPerfAccumulate("evu_preflight_serial_stall", stalled)
    XSPerfAccumulate("evu_load_slot_capture", slots.io.capture.fire)
    XSPerfAccumulate("evu_load_capture_before_head", slots.io.capture.fire && source.bits.robIdx =/= robHead)
    XSPerfAccumulate("evu_load_both_reserved", slots.io.full)
    XSPerfAccumulate("evu_load_check_bypass", check.req.fire && load && generalBarrier)
    for ((name, selected) <- Seq("load" -> load, "store" -> store)) {
      val active = stalled && selected
      XSPerfAccumulate(s"evu_preflight_${name}_control_only", active && barrier.control && !barrier.status)
      XSPerfAccumulate(s"evu_preflight_${name}_status_only", active && !barrier.control && barrier.status)
      XSPerfAccumulate(s"evu_preflight_${name}_both", active && barrier.control && barrier.status)
    }
    val arithLoadOpportunity = memory && !checking && f7 === EvuIsa.load && !barrier.control &&
      barrier.arithmeticOnly && stable && !redirect.valid
    XSPerfAccumulate("evu_preflight_load_arith_only_published", arithLoadOpportunity)
    check.req.bits.base := payload.base
    check.req.bits.length := effectiveLength
    check.req.bits.read := load
    check.req.bits.write := store
    // Custom ordinary stores are unmasked and unit-stride. Only a 64-byte
    // VMU beat with full aligned coverage is guaranteed to avoid external RMW.
    check.req.bits.fullLineWrite := (p(ExternalVectorKey).mlenBits == 512).B &&
      f7 === EvuIsa.store && !payload.base(5, 0).orR && !effectiveLength(5, 0).orR
    check.req.bits.alignment := effectiveSew
    check.req.bits.illegal := Mux(queued, q.bits.cmd.config.vill, csr.config.vill) ||
      ((load || store) && effectiveSew > EvuIsa.maxSupportedSew.U)
    // A revoke also invalidates a response already waiting for the WB port.
    // Redispatch the head as an illegal instruction after cancelling the lookup.
    check.cancel := redirect.valid || (checking && !csr.permitted)
    check.resp.ready := memory && checking && sink.ready && !check.cancel
    when(check.req.fire) { checking := true.B }
    when(check.resp.fire || check.cancel) { checking := false.B }
    val result = checking && check.resp.valid && !check.cancel
    val queuedWb = queued && (invalidQueued || result) && !check.cancel
    val directReady = !queuedWb && (!directMemory || (!queued && result))
    sink.valid := queuedWb || (direct && directReady)
    source.ready := !killed && Mux(bufferLoad, slots.io.capture.ready, sink.ready && directReady)
    q.ready := queuedWb && sink.ready
    sink.bits := Mux(queuedWb, q.bits.wb, source.bits)
    val checkedConfig = RegEnable(Mux(queued, q.bits.cmd.config, csr.config), check.req.fire)
    val translated = WireInit(incoming)
    translated.config := Mux(configOp, incoming.config, csr.config)
    when(isEvu && !queuedWb) { sink.bits.amuCtrl.get.data := translated.asUInt }
    when(queuedWb) {
      sink.bits.amuCtrl.get.data := EvuCoreCommandData.expand(q.bits.cmd, 0.U.asTypeOf(incoming.pageMap), ep).asUInt
    }
    when(Mux(queuedWb, invalidQueued, isEvu && permissionFault)) {
      sink.bits.exceptionVec.get(ExceptionNO.illegalInstr) := true.B
    }
    when(result && memory) {
      val checked = EvuCoreCommandData.expand(payload, check.resp.bits.pageMap, ep)
      checked.config := checkedConfig
      sink.bits.amuCtrl.get.data := checked.asUInt
      val fault = check.resp.bits.fault
      val write = check.resp.bits.write
      val exceptions = sink.bits.exceptionVec.get
      exceptions := 0.U.asTypeOf(exceptions)
      exceptions(ExceptionNO.illegalInstr) := fault === EvuAddressFault.Illegal
      exceptions(ExceptionNO.loadAddrMisaligned) := !write && fault === EvuAddressFault.Misaligned
      exceptions(ExceptionNO.storeAddrMisaligned) := write && fault === EvuAddressFault.Misaligned
      exceptions(ExceptionNO.loadAccessFault) := !write && fault === EvuAddressFault.Access
      exceptions(ExceptionNO.storeAccessFault) := write && fault === EvuAddressFault.Access
      exceptions(ExceptionNO.loadPageFault) := !write && fault === EvuAddressFault.Page
      exceptions(ExceptionNO.storePageFault) := write && fault === EvuAddressFault.Page
      exceptions(ExceptionNO.loadGuestPageFault) := !write && fault === EvuAddressFault.GuestPage
      exceptions(ExceptionNO.storeGuestPageFault) := write && fault === EvuAddressFault.GuestPage
    }
    val checkOwner = RegEnable(tag, check.req.fire)
    when(checking && !check.cancel) {
      assert(memory && tag === checkOwner, "preflight response owner changed before writeback")
    }
    check.perf.foreach { perf =>
      // Pair with the existing EVU-CMD accept by PC/occurrence. A redirected
      // preflight has no obligation to reach the EVU.
      val commandTrace = freechips.rocketchip.util.PlusArg("evu_cmd_trace", width = 1)
      val ticks = RegInit(0.U(64.W))
      ticks := ticks + 1.U
      val reqPc = RegEnable(payload.pc, check.req.fire)
      val reqTag = RegEnable(tag, check.req.fire)
      val previousOpportunity = RegNext(arithLoadOpportunity, false.B)
      val previousTag = RegEnable(tag, arithLoadOpportunity)
      when(commandTrace.asBool && !inReset) {
        when(slots.io.capture.fire) {
          printf(p"[EVU-LOAD-SLOT] event=capture rob=${source.bits.robIdx.asUInt} pc=0x${Hexadecimal(incoming.pc)} head=${robHead.asUInt} t=${ticks}\n")
        }
        when(arithLoadOpportunity && (!previousOpportunity || previousTag =/= tag)) {
          printf(p"[EVU-PREFLIGHT-CMD] event=opportunity rob=${tag} pc=0x${Hexadecimal(payload.pc)} t=${ticks}\n")
        }
        when(check.req.fire) {
          printf(p"[EVU-PREFLIGHT-CMD] event=req rob=${tag} pc=0x${Hexadecimal(payload.pc)} write=${store} bytes=${effectiveLength} t=${ticks}\n")
        }
        when(check.cancel && (checking || check.req.fire)) {
          printf(p"[EVU-PREFLIGHT-CMD] event=cancel rob=${Mux(checking, reqTag, tag)} pc=0x${Hexadecimal(Mux(checking, reqPc, payload.pc))} t=${ticks}\n")
        }.elsewhen(check.resp.fire) {
          printf(p"[EVU-PREFLIGHT-CMD] event=resp rob=${reqTag} pc=0x${Hexadecimal(reqPc)} fault=${check.resp.bits.fault} t=${ticks}\n")
        }
      }
      val trace = freechips.rocketchip.util.PlusArg("evu_mem_trace", width = 1)
      val fenceIndex = RegInit(0.U(32.W))
      val outputWait = RegInit(0.U(64.W))
      val serialWait = RegInit(0.U(64.W))
      when(memory && !check.resp.valid && !check.cancel) { outputWait := outputWait + 1.U }
      when(stalled) { serialWait := serialWait + 1.U }
      when(csr.completed.valid && csr.completed.bits.opcode === xiangshan.evu.EvuOpcode.Fence) {
        fenceIndex := fenceIndex + 1.U
        when(trace.asBool) {
          printf(p"[EVU-PREFLIGHT] fence=${fenceIndex} cmd=${perf.commands} busy=${perf.busy} checks=${perf.checks} reads=${perf.reads} writes=${perf.writes} fullstore=${perf.fullStores} lookupstall=${perf.lookupStall} tlbreq=${perf.tlbRequests} tlbmiss=${perf.tlbMisses} sbwait=${perf.sbWait} outputwait=${outputWait} serialwait=${serialWait}\n")
        }
      }
    }
    (slots.io.full, slots.io.pending)
  }
}
