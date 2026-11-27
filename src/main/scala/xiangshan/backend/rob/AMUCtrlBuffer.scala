package xiangshan.backend.rob

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import difftest._
import org.chipsalliance.cde.config.Parameters
import utility._
import utils.DebugMem
import utils.OptionWrapper
import xiangshan._
import xiangshan.backend.BackendParams
import xiangshan.backend.Bundles.DynInst
import xiangshan.backend.fu.FuType
import xiangshan.backend.fu.matrix.Bundles._
import xiangshan.evu.{EvuAmuCommandData, EvuCompletion, EvuCoreCommand, EvuExecutionResult, EvuIsa, EvuPageMap, EvuPageMapPool}

class AmuCtrlBufferIO()(implicit val p: Parameters, val params: BackendParams) extends Bundle with HasXSParameter {
  // rob enq
  class EnqBundle()(implicit val p: Parameters) extends Bundle with HasXSParameter {
    val valid = Bool()  // Req valid and canEnqueue
    val reqValid = Bool()  // Req valid
    val allocPtr = new RobPtr
    val needAMU = Bool()
    val evuMemory = Bool()
    val pc = Option.when(env.EnableDifftest && HasMatrixExtension)(UInt(VAddrBits.W))
    val pcTransType = Option.when(env.EnableDifftest && HasMatrixExtension)(new AddrTransType)
  }
  val enq = Input(Vec(RenameWidth, new EnqBundle))
  val dispatchEvuMaps = Option.when(p(ExternalVectorKey).enable)(Input(UInt(log2Ceil(RenameWidth + 1).W)))
  val canEnqueue = Output(Bool())
  val canEnqueueForDispatch = Output(Bool())
  val evuBusy = Output(Bool())
  val evuDrainWork = Option.when(p(ExternalVectorKey).enable)(Output(new Bundle {
    val nonSetvli = Bool()
    val setvliOrStatus = Bool()
  }))
  val evuCommittedBusy = Output(Bool())
  val evuPreflightBusy = Output(Bool())
  val evuPreflightReasons = Output(new xiangshan.evu.EvuPreflightReasons)
  val configPublished = Input(Valid(UInt((new RobPtr).getWidth.W)))
  val evuRetired = Output(Valid(new xiangshan.evu.EvuRetiredCommand(p(ExternalVectorKey), (new RobPtr).getWidth)))

  // rob wb
  val wb = Flipped(params.genWrite2CtrlBundles)

  // Commit
  class DeqCommitBundle extends Bundle {
    val ptr = new RobPtr
    val valid = Bool()
  }
  val deqCommit = Input(Vec(CommitWidth, new DeqCommitBundle))

  // Redirect (no need to walk)
  class RedirectBundle extends Bundle {
    val valid = Bool()
    val all = Bool()
    val begin = UInt(log2Up(RobSize).W)
    val end = UInt(log2Up(RobSize).W)
  }
  val redirect = Input(new RedirectBundle)

  // To Amu
  val toAMU = Vec(CommitWidth, DecoupledIO(new AmuCtrlIO))
  val evuCompletion = OptionWrapper(p(ExternalVectorKey).enable, Flipped(Decoupled(new EvuExecutionResult(p(ExternalVectorKey)))))
  // One-cycle, in-order status pulse for the CSR/ROB side. EVU commands are
  // dispatched after the scalar instruction has retired, so their flags
  // cannot use the ordinary EXU writeback slot.
  val evuStatus = OptionWrapper(p(ExternalVectorKey).enable,
    Output(Valid(new EvuCompletion(p(ExternalVectorKey)))))
  val evuDirtyVs = Output(Bool())

  // If we enable difftest, there will be hartid
  val hartId = Option.when(env.EnableDifftest && HasMatrixExtension)(Input(UInt(hartIdLen.W)))
}


class AmuStoredCtrl(implicit p: Parameters) extends XSBundle {
  val op = UInt(2.W)
  val evu = Bool()
  val data = UInt((if (p(ExternalVectorKey).enable)
    math.max(150, (new EvuAmuCommandData(p(ExternalVectorKey))).getWidth) else 150).W)
  def evuData: EvuAmuCommandData = data.asTypeOf(new EvuAmuCommandData(p(ExternalVectorKey)))
  val pc = Option.when(env.EnableDifftest)(UInt(64.W))
  val coreid = Option.when(env.EnableDifftest)(UInt(hartIdLen.W))
}

class AmuCtrlEntry(implicit p: Parameters) extends XSBundle {
  val valid = Bool()
  val needAMU = Bool()
  val writebacked = Bool()
  val committed = Bool()
  val amuCtrl = new AmuStoredCtrl
  val mapSlot = Option.when(p(ExternalVectorKey).enable)(UInt(5.W))
  val canDeq = Bool()
  val evuAccepted = Bool()
  val evuException = Bool()
  // Preserve the full EVU architectural completion until this AMU entry is
  // retired. The old implementation kept only exception and silently dropped
  // fflags/vxsat/cause.
  val evuFflags = UInt(5.W)
  val evuVxsat = Bool()
  val evuCause = UInt(4.W)

  def amuReqValid = valid && needAMU && writebacked && committed && !canDeq && !evuAccepted

  def checkSanity(name: String) = {
    // Check valid sequence: canDeq -> committed -> writebacked -> valid
    assert(!canDeq || committed, s"$name: committed must be valid when canDeq is valid")
    assert(!committed || (!needAMU || writebacked), s"$name: writebacked must be valid when committed is valid") 
    assert(!committed || valid, s"$name: valid must be valid when committed is valid")
    assert(!needAMU || valid, s"$name: valid must be valid when needAMU is valid")
    assert(!writebacked || valid, s"$name: valid must be valid when writebacked is valid")
  }
}

object AmuCtrlEntry {
  def apply()(implicit p: Parameters) = {
    new AmuCtrlEntry
  }

  def zero(implicit p: Parameters) = {
    val default_zero = 0.U.asTypeOf(new AmuCtrlEntry)
    assert(default_zero.getWidth > 0)
    default_zero
  }
}

object AmuCtrlBuffer {
  def needsEvuMap(inst: DynInst): Bool = inst.fuType === FuType.evu.U &&
    (EvuIsa.isLoad(inst.instr(31, 25)) || EvuIsa.isStore(inst.instr(31, 25)))
}

/**
 * AMU Control Buffer
 *
 * Purpose:
 * A dedicated buffer parallel to ROB for storing AMU control signals, avoiding storing them in ROB directly.
 * The buffer size matches ROB size.
 *
 * Operation:
 * 1. Instructions enter this buffer synchronously when entering ROB
 * 2. When buffer is full, it will backpressure ROB enqueue
 * 3. Synchronizes with ROB writeback and flush, no need for walking to rebuild context
 * 4. After ROB commit, starts handshake with AMU and dequeues after handshake fires
 */
class AmuCtrlBuffer()(implicit override val p: Parameters, val params: BackendParams) extends XSModule
  with HasXSParameter with HasCircularQueuePtrHelper {

  val io = IO(new AmuCtrlBufferIO)
  val evuStatusReg = RegInit(0.U.asTypeOf(new EvuCompletion(p(ExternalVectorKey))))
  val evuStatusPulse = RegInit(false.B)
  io.evuStatus.foreach { status =>
    status.valid := evuStatusPulse
    status.bits := evuStatusReg
  }
  io.evuDirtyVs := evuStatusPulse && !evuStatusReg.exception && evuStatusReg.dirtyVs
  when (evuStatusPulse) { evuStatusPulse := false.B }

  def connectROB(robImp: RobImp) = {
    io.configPublished := robImp.io.evuConfigPublished
    io.dispatchEvuMaps.foreach(_ := robImp.io.dispatchEvuMaps.get)
    for (i <- 0 until RenameWidth) {
      io.enq(i).valid := robImp.io.enq.req(i).valid && robImp.canEnqueue(i) && !robImp.io.redirect.valid
      io.enq(i).reqValid := robImp.io.enq.req(i).valid
      io.enq(i).allocPtr := robImp.allocatePtrVec(i)
      io.enq(i).needAMU := robImp.io.enq.req(i).bits.needAmuCtrl.get
      val inst = robImp.io.enq.req(i).bits
      io.enq(i).evuMemory := p(ExternalVectorKey).enable.B && AmuCtrlBuffer.needsEvuMap(inst)

      if (env.EnableDifftest && HasMatrixExtension) {
        io.enq(i).pc.get := robImp.io.enq.req(i).bits.pc
        io.enq(i).pcTransType.get := robImp.io.debugInstrAddrTransType
      }
    }
    io.wb := robImp.io.writeback
    io.deqCommit.zip(robImp.deqPtrVec).zip(robImp.io.commits.commitValid) foreach { case ((deq, ptr), valid) =>
      deq.ptr := ptr
      deq.valid := valid && robImp.io.commits.isCommit
    }
    io.redirect.valid := robImp.redirectValidReg
    io.redirect.all := robImp.redirectAll
    io.redirect.begin := robImp.redirectBegin
    io.redirect.end := robImp.redirectEnd
    robImp.io.amuCtrl.get <> io.toAMU
    if (env.EnableDifftest && HasMatrixExtension) {
      io.hartId.get := robImp.io.hartId
    }
  }

  val amuCtrlEntries = RegInit(VecInit.fill(RobSize)(AmuCtrlEntry.zero))
  val amuCtrlWb = io.wb.filter(_.bits.amuCtrl.nonEmpty).toSeq
  val mapPool = Option.when(p(ExternalVectorKey).enable) {
    Module(new EvuPageMapPool(p(ExternalVectorKey), RobSize, RenameWidth, amuCtrlWb.size, CommitWidth))
  }
  val cancelledOwners = VecInit((0 until RobSize).map { i =>
    io.redirect.valid && (io.redirect.all || Mux(io.redirect.end > io.redirect.begin,
      i.U > io.redirect.begin && i.U < io.redirect.end,
      i.U > io.redirect.begin || i.U < io.redirect.end))
  }).asUInt
  val (mapCapacityReady, mapDispatchReady) = mapPool.map { pool =>
    pool.io.cancelOwners := cancelledOwners
    pool.io.release := 0.U
    for (i <- 0 until RenameWidth) {
      pool.io.reserve(i).valid := io.enq(i).valid && io.enq(i).evuMemory && !io.redirect.valid
      pool.io.reserve(i).bits := io.enq(i).allocPtr.value
    }
    for ((wb, i) <- amuCtrlWb.zipWithIndex) {
      val payload = wb.bits.amuCtrl.get.data.asTypeOf(new EvuCoreCommand(p(ExternalVectorKey)))
      when(wb.valid && wb.bits.amuCtrl.get.evu) {
        assert(payload.length === p(ExternalVectorKey).vlenBytes.U &&
          payload.vl === EvuIsa.cpuCommandVl(p(ExternalVectorKey), payload.instr, payload.config.vl),
          "EVU writeback does not fit the AMU storage encoding")
      }
      val memory = EvuIsa.isLoad(payload.instr(31, 25)) || EvuIsa.isStore(payload.instr(31, 25))
      pool.io.write(i).valid := wb.valid && wb.bits.amuCtrl.get.evu && memory && !cancelledOwners(wb.bits.robIdx.value)
      pool.io.write(i).bits.slot := amuCtrlEntries(wb.bits.robIdx.value).mapSlot.get
      pool.io.write(i).bits.owner := wb.bits.robIdx.value
      pool.io.write(i).bits.map := payload.pageMap
      when(pool.io.write(i).valid && payload.pageMap.enabled) {
        assert(payload.pageMap.base === payload.base, "Preflight checked a different command base")
      }
    }
    val requested = PopCount(io.enq.map(e => e.reqValid && e.evuMemory))
    val grants = EvuPageMapPool.admission(pool.io.occupied, requested, io.dispatchEvuMaps.get, RenameWidth)
    when(io.enq.map(_.reqValid).reduce(_ || _)) {
      assert(grants._1, "dispatched ROB group lost its CPU translation capacity")
    }
    grants
  }.getOrElse((true.B, true.B))

  def expandControl(entry: AmuCtrlEntry, map: EvuPageMap): AmuCtrlIO = {
    val result = Wire(new AmuCtrlIO)
    result.op := entry.amuCtrl.op
    result.evu := entry.amuCtrl.evu
    val identity = entry.amuCtrl.evuData
    val memory = EvuIsa.isLoad(identity.instr(31, 25)) || EvuIsa.isStore(identity.instr(31, 25))
    val commandMap = Mux(memory, map, 0.U.asTypeOf(map))
    result.data := Mux(entry.amuCtrl.evu,
      identity.expand(commandMap).asUInt, entry.amuCtrl.data)
    result.pc.foreach(_ := entry.amuCtrl.pc.get)
    result.coreid.foreach(_ := entry.amuCtrl.coreid.get)
    result
  }

  // Difftest
  val dt_pc = Option.when(env.EnableDifftest && HasMatrixExtension)(DebugMem(RobSize, UInt(VAddrBits.W)))
  val dt_pcTransType = Option.when(env.EnableDifftest && HasMatrixExtension)(Mem(RobSize, new AddrTransType))

  // Calculate number of valid entries and new entries to be enqueued
  val numValidEntries = PopCount(amuCtrlEntries.map(_.valid))
  val numNewEntries = PopCount(io.enq.map(_.reqValid))

  // Check if there's enough space in the queue
  val canEnqueue = GatedValidRegNext(
    numValidEntries + numNewEntries <= (RobSize - RenameWidth).U,
    true.B
  )
  val canEnqueueForDispatch = GatedValidRegNext(
    numValidEntries + numNewEntries <= (RobSize - RenameWidth * 2).U,
    true.B
  )
  io.canEnqueue := canEnqueue && mapCapacityReady
  io.canEnqueueForDispatch := canEnqueueForDispatch && mapDispatchReady
  XSPerfAccumulate("evu_translation_pool_full", !mapDispatchReady)

  // Enqueue (Sync with outer ROB)
  // DynInst does not carry amuCtrl info,
  // so we only need to mark valid for entries who need amuCtrl.
  for (i <- 0 until RobSize) {
    val indexMatch = io.enq.map(_.allocPtr.value === i.U)
    val enqOH = VecInit(io.enq.zip(indexMatch).map(x => x._1.valid && x._2))
    val needOH = VecInit(io.enq.zip(enqOH).map(x => x._1.needAMU && x._2))
    val entry = amuCtrlEntries(i)
    when (enqOH.asUInt.orR) {
      entry := AmuCtrlEntry.zero
      entry.valid := true.B
      entry.needAMU := needOH.asUInt.orR
      entry.mapSlot.foreach { slot =>
        slot := Mux1H(io.enq.zip(enqOH).map { case (enq, hit) => hit && enq.evuMemory }, mapPool.get.io.slots)
      }
      if (env.EnableDifftest && HasMatrixExtension) {
        val pcData = Mux1H(
          io.enq.zip(indexMatch).map { case (enq, imatch) => imatch && enq.valid },
          io.enq.map(_.pc.get)
        )
        val pcTransTypeData = Mux1H(
          io.enq.zip(indexMatch).map { case (enq, imatch) => imatch && enq.valid },
          io.enq.map(_.pcTransType.get)
        )
        
        dt_pc.get(i) := pcData
        dt_pcTransType.get(i) := pcTransTypeData
      }
    }
  }

  // Writeback (Sync with outer ROB)
  for (i <- 0 until RobSize) {
    val amu_data = amuCtrlWb.map{ wb =>
      val valid_match = wb.valid && wb.bits.robIdx.value === i.U
      Mux(valid_match, wb.bits.amuCtrl.get.asUInt, 0.U)
    }.reduce(_ | _)

    val entry = amuCtrlEntries(i)
    assert(!amu_data.orR || entry.valid, s"AMUCtrl entry $i is invalid but has amu_data")
    when (amu_data.orR) {
      entry.writebacked := true.B
      val incoming = amu_data.asTypeOf(new AmuCtrlIO)
      entry.amuCtrl.op := incoming.op
      entry.amuCtrl.evu := incoming.evu
      entry.amuCtrl.data := Mux(incoming.evu, EvuAmuCommandData.pack(
        incoming.data.asTypeOf(new EvuCoreCommand(p(ExternalVectorKey))), p(ExternalVectorKey)).asUInt, incoming.data)
      // The matrix probe observes the serialized output after arbitration.
      if (env.EnableDifftest && HasMatrixExtension) {
        entry.amuCtrl.pc.get := Mux(dt_pcTransType.get(i).shouldBeSext, SignExt(dt_pc.get(i), XLEN), dt_pc.get(i))
        entry.amuCtrl.coreid.get := io.hartId.get
      }
    }
  }

  // Commit (Sync with outer ROB)
  for (i <- 0 until RobSize) {
    val deqValid = io.deqCommit.map(x => x.ptr.value === i.U && x.valid)
    val commitCond = deqValid.reduce(_ || _)
    when (commitCond) {
      assert(amuCtrlEntries(i).valid, s"AMUCtrlBuffer: amuCtrlEntries[$i] is invalid but will commit")
      amuCtrlEntries(i).committed := true.B
      when (!amuCtrlEntries(i).needAMU) {
        amuCtrlEntries(i).canDeq := true.B
      }
    }
  }

  val retiringEvu = io.deqCommit.map { deq =>
    val entry = amuCtrlEntries(deq.ptr.value)
    deq.valid && entry.needAMU && entry.writebacked && entry.amuCtrl.evu
  }
  // A legal SETVLI may retire beside older arithmetic. The scalar CSR port
  // must see the configuration, not an arbitrary arithmetic command. The AMU
  // still commits every lane and sends all commands to the EVU in order.
  val retiringConfig = retiringEvu.zip(io.deqCommit).map { case (v, deq) =>
    val op = amuCtrlEntries(deq.ptr.value).amuCtrl.evuData.instr(31, 25)
    v && (op === EvuIsa.setvli || op === EvuIsa.setvl)
  }
  val hasConfig = retiringConfig.reduce(_ || _)
  val selected = retiringEvu.zip(retiringConfig).map { case (v, cfg) => Mux(hasConfig, cfg, v) }
  assert(PopCount(retiringConfig) <= 1.U)
  io.evuRetired.valid := retiringEvu.reduce(_ || _)
  io.evuRetired.bits.robTag := PriorityMux(selected.zip(io.deqCommit.map(_.ptr.asUInt)))
  io.evuRetired.bits.command := PriorityMux(selected.zip(io.deqCommit.zipWithIndex.map { case (deq, i) =>
    val entry = amuCtrlEntries(deq.ptr.value)
    // CSR retirement consumes instruction/config identity, never translations.
    expandControl(entry, 0.U.asTypeOf(new EvuPageMap(p(ExternalVectorKey)))).data
      .asTypeOf(new EvuCoreCommand(p(ExternalVectorKey)))
  }))

  // Redirect (Sync with outer ROB)
  for (i <- 0 until RobSize) {
    val needFlush = io.redirect.valid && (Mux(
      (io.redirect.end > io.redirect.begin),
        (i.U > io.redirect.begin) && (i.U < io.redirect.end),
        (i.U > io.redirect.begin) || (i.U < io.redirect.end)
      ) || io.redirect.all)

    when (needFlush) {
      amuCtrlEntries(i) := AmuCtrlEntry.zero
    }
  }

  // To AMU
  val deqPtr = RegInit(0.U.asTypeOf(new RobPtr))
  val deqEntries = (0 until CommitWidth).map(i => amuCtrlEntries((deqPtr + i.U).value))
  val deqCount = PriorityEncoder(deqEntries.map(x => !x.canDeq) :+ true.B)
  val nextDeqPtr = deqPtr + deqCount
  val separateIssue = p(ExternalVectorKey).enable && p(ExternalVectorKey).decoupledAmuIssue
  val issuePtr = RegInit(0.U.asTypeOf(new RobPtr))
  val dispatchPtr = if (separateIssue) issuePtr else deqPtr
  val issueEntries = (0 until CommitWidth).map(i => amuCtrlEntries((dispatchPtr + i.U).value))
  val issueBarrier = RegInit(false.B)
  val issuedOutstanding = amuCtrlEntries.map(e => e.valid && e.evuAccepted && !e.canDeq).reduce(_ || _)
  val statusDrain = RegInit(0.U(4.W))
  statusDrain := Cat(statusDrain(2, 0), io.evuCompletion.map(_.fire).getOrElse(false.B))
  when(issueBarrier && !issuedOutstanding && !statusDrain.orR && !evuStatusPulse) {
    issueBarrier := false.B
  }
  def issuedOrSkipped(e: AmuCtrlEntry): Bool =
    e.valid && e.committed && (!e.needAMU || e.canDeq || e.evuAccepted)

  class AmuCtrlTableEntry extends Bundle {
    val op = UInt(2.W)
    val mma = new AmuMmaIO
    val mls = new AmuLsuIO
    val mrelease = new AmuReleaseIO2CUTE
    val arith = new AmuArithIO
  }
  val amuCtrl_table = ChiselDB.createTable(s"AMUCtrl_table", new AmuCtrlTableEntry, basicDB = true)
  val memIssueTrace = Option.when(p(ExternalVectorKey).enable && p(ExternalVectorKey).enableDifftest) {
    val ticks = RegInit(0.U(64.W))
    ticks := ticks + 1.U
    (freechips.rocketchip.util.PlusArg("evu_cmd_trace", width = 1), ticks)
  }

  io.toAMU.zipWithIndex.foreach { case (amuCtrl, i) =>
    val deqEntry = issueEntries(i)
    val olderAmuReqValid = if (i == 0) false.B else issueEntries.take(i).map(_.amuReqValid).reduce(_ || _)
    val olderIssued = issueEntries.take(i).map(issuedOrSkipped).foldLeft(true.B)(_ && _)
    deqEntry.checkSanity(s"AMUCtrlBuffer: deqEntry[$i]")
    amuCtrl.valid := deqEntry.amuReqValid && !io.redirect.valid &&
      (if (separateIssue) olderIssued && !issueBarrier else true.B)
    mapPool.foreach { pool =>
      pool.io.read(i) := deqEntry.mapSlot.get
      pool.io.readBase(i) := deqEntry.amuCtrl.evuData.base
    }
    val expanded = expandControl(deqEntry, mapPool.map(_.io.data(i)).getOrElse(
      0.U.asTypeOf(new EvuPageMap(p(ExternalVectorKey)))))
    amuCtrl.bits := Mux(deqEntry.amuReqValid, expanded, 0.U.asTypeOf(new AmuCtrlIO))
    when (amuCtrl.fire) {
      // XSCore serializes these lanes through a fixed-priority arbiter.
      assert(!olderAmuReqValid, s"AMUCtrlBuffer: toAMU[$i] fired before an older AMU request")
      assert(!deqEntry.canDeq, s"AMUCtrlBuffer: deqEntry[$i] is already set to canDeq")
      when (amuCtrl.bits.evu) {
        deqEntry.evuAccepted := true.B
        val f7 = amuCtrl.bits.data.asTypeOf(new EvuCoreCommand(p(ExternalVectorKey))).instr(31, 25)
        memIssueTrace.foreach { case (trace, ticks) =>
          when(trace.asBool && (EvuIsa.isLoad(f7) || EvuIsa.isStore(f7))) {
            val pc = amuCtrl.bits.data.asTypeOf(new EvuCoreCommand(p(ExternalVectorKey))).pc
            printf(p"[EVU-PREFLIGHT-CMD] event=send rob=${(dispatchPtr + i.U).asUInt} pc=0x${Hexadecimal(pc)} t=${ticks}\n")
          }
        }
        when(separateIssue.B && f7 === xiangshan.evu.EvuIsa.fence) {
          issueBarrier := true.B
        }
      }.otherwise {
        deqEntry.canDeq := true.B
      }
    }
    val mmaio = amuCtrl.bits.data.asTypeOf(new AmuMmaIO)
    val mlsio = amuCtrl.bits.data.asTypeOf(new AmuLsuIO)
    val mreleaseio = amuCtrl.bits.data.asTypeOf(new AmuReleaseIO2CUTE)
    val arithio = amuCtrl.bits.data.asTypeOf(new AmuArithIO)
    val amuCtrl_table_entry = Wire(new AmuCtrlTableEntry)
    amuCtrl_table_entry.op := amuCtrl.bits.op
    when(amuCtrl.bits.isMma() && !amuCtrl.bits.evu) {
      amuCtrl_table_entry.mma := mmaio
    }.otherwise {
      amuCtrl_table_entry.mma := 0.U.asTypeOf(new AmuMmaIO)
    }
    when(amuCtrl.bits.isMls() && !amuCtrl.bits.evu) {
      amuCtrl_table_entry.mls := mlsio
    }.otherwise {
      amuCtrl_table_entry.mls := 0.U.asTypeOf(new AmuLsuIO)
    }
    when(amuCtrl.bits.isRelease() && !amuCtrl.bits.evu) {
      amuCtrl_table_entry.mrelease := mreleaseio
    }.otherwise {
      amuCtrl_table_entry.mrelease := 0.U.asTypeOf(new AmuReleaseIO2CUTE)
    }
    when(amuCtrl.bits.isArith() && !amuCtrl.bits.evu) {
      amuCtrl_table_entry.arith := arithio
    }.otherwise {
      amuCtrl_table_entry.arith := 0.U.asTypeOf(new AmuArithIO)
    }
    amuCtrl_table.log(
      data = amuCtrl_table_entry,
      en = amuCtrl.fire && !amuCtrl.bits.evu,
      site = s"AMUCtrl_table",
      clock = clock,
      reset = reset
    )

  }

  mapPool.foreach { pool =>
    pool.io.release := io.toAMU.zip(issueEntries).map { case (port, entry) =>
      val f7 = port.bits.data.asTypeOf(new EvuCoreCommand(p(ExternalVectorKey))).instr(31, 25)
      Mux(port.fire && port.bits.evu && (EvuIsa.isLoad(f7) || EvuIsa.isStore(f7)),
        UIntToOH(entry.mapSlot.get, 32), 0.U(32.W))
    }.reduce(_ | _)
  }

  // Skip only the completed prefix being cleared this cycle. The target is
  // still the oldest unfinished entry, never a younger command behind it.
  val arithmeticCompletion = WireInit(false.B)
  io.evuCompletion.foreach { completion =>
    val completionPtr = if (p(ExternalVectorKey).completionLookahead) nextDeqPtr else deqPtr
    val entry = amuCtrlEntries(completionPtr.value)
    val f7 = entry.amuCtrl.evuData.instr(31, 25)
    arithmeticCompletion := !completion.bits.exception && !EvuIsa.isLoad(f7) && !EvuIsa.isStore(f7) &&
      f7 =/= EvuIsa.fence && f7 =/= EvuIsa.setvli && f7 =/= EvuIsa.setvl
    completion.ready := entry.valid && entry.needAMU && entry.writebacked && entry.committed &&
      entry.amuCtrl.evu && entry.evuAccepted && !entry.canDeq && !io.redirect.valid
    when (completion.fire) {
      entry.canDeq := true.B
      entry.evuException := completion.bits.exception
      entry.evuFflags := completion.bits.fflags
      entry.evuVxsat := completion.bits.vxsat
      entry.evuCause := completion.bits.cause
      evuStatusReg.resultFields := completion.bits
      evuStatusReg.pc := entry.amuCtrl.evuData.pc
      evuStatusPulse := true.B
      for (i <- 0 until CommitWidth) {
        assert(!(i.U < deqCount && completionPtr === deqPtr + i.U),
          "EVU completion targeted an AMU entry being cleared")
      }
    }
    val previousFire = RegNext(completion.fire, false.B)
    XSPerfAccumulate("evu_amu_completion_fire", completion.fire)
    XSPerfAccumulate("evu_amu_completion_blocked", completion.valid && !completion.ready)
    XSPerfAccumulate("evu_amu_completion_lookahead", completion.fire && completionPtr =/= deqPtr)
    XSPerfAccumulate("evu_amu_completion_consecutive", completion.fire && previousFire)
    if (env.EnableDifftest) {
      val event = DifftestModule(new DiffEvuCompletionEvent, delay = 3)
      val payload = entry.amuCtrl.evuData
      event.valid := completion.fire
      event.coreid := io.hartId.get
      event.pc := entry.amuCtrl.pc.get
      event.instr := payload.instr
      event.fflags := completion.bits.fflags
      event.vxsat := completion.bits.vxsat
      event.exception := completion.bits.exception
      event.cause := completion.bits.cause
    }
  }

  // A retired FENCE must keep dispatch blocked through the ROB ->
  // backend -> CSR status pipeline as well as through the final write Ack.
  val busyEntries = amuCtrlEntries.map(e =>
    e.valid && e.needAMU && e.writebacked && e.amuCtrl.evu && !e.canDeq
  )
  io.evuBusy := busyEntries.reduce(_ || _) || statusDrain.orR
  io.evuDrainWork.foreach { work =>
    val setvli = amuCtrlEntries.map(_.amuCtrl.evuData.instr(31, 25) === xiangshan.evu.EvuIsa.setvli)
    work.nonSetvli := busyEntries.zip(setvli).map { case (b, s) => b && !s }.reduce(_ || _)
    work.setvliOrStatus := statusDrain.orR || busyEntries.zip(setvli).map { case (b, s) => b && s }.reduce(_ || _)
  }
  // A faulting scalar EVU instruction has not committed and must never wait
  // for its own nonexistent external completion before taking the trap.
  io.evuCommittedBusy := amuCtrlEntries.map(e =>
    e.valid && e.needAMU && e.committed && e.writebacked && e.amuCtrl.evu && !e.canDeq
  ).reduce(_ || _) || statusDrain.orR || evuStatusPulse
  // Preflight may walk pages beside older ordinary memory or arithmetic. Keep it behind
  // older control commands and the status pipeline, not ordinary memory.
  // The VMU now owns per-command translations, ordering and asynchronous faults.
  // Only the most recently retired immediate configuration can qualify. A
  // second live control, including an older SETVLI, stays conservative.
  val retiredSetvli = io.evuRetired.valid && io.evuRetired.bits.command.instr(31, 25) === EvuIsa.setvli
  val configOwner = RegEnable(io.evuRetired.bits.robTag.asTypeOf(new RobPtr), retiredSetvli)
  val ownerLive = RegInit(false.B)
  val ownerPublished = RegInit(false.B)
  val reuseOwner = io.enq.map(e => e.valid && e.allocPtr.value === configOwner.value).reduce(_ || _)
  when(reuseOwner) { ownerLive := false.B; ownerPublished := false.B }
  when(ownerLive && !reuseOwner && io.configPublished.valid && io.configPublished.bits === configOwner.asUInt) {
    ownerPublished := true.B
  }
  when(retiredSetvli) { ownerLive := true.B; ownerPublished := false.B }
  val controls = amuCtrlEntries.map { e =>
    val f7 = e.amuCtrl.evuData.instr(31, 25)
    val serial = f7 === xiangshan.evu.EvuIsa.fence || f7 === xiangshan.evu.EvuIsa.setvli ||
      f7 === xiangshan.evu.EvuIsa.setvl
    e.valid && e.needAMU && e.writebacked && e.amuCtrl.evu && !e.canDeq && serial
  }
  val preflightControl = controls.reduce(_ || _)
  io.evuPreflightReasons.unpublishedControl := controls.zipWithIndex.map { case (busy, i) =>
    busy && !(ownerLive && ownerPublished && configOwner.value === i.U)
  }.reduce(_ || _)
  io.evuPreflightBusy := preflightControl || statusDrain.orR
  io.evuPreflightReasons.control := preflightControl
  io.evuPreflightReasons.status := statusDrain.orR
  val completionFire = io.evuCompletion.map(_.fire).getOrElse(false.B)
  io.evuPreflightReasons.otherCompletion := completionFire && !arithmeticCompletion
  io.evuPreflightReasons.arithmeticOnly :=
    xiangshan.evu.EvuPreflightReasons.arithmeticOnly(statusDrain, completionFire, arithmeticCompletion)

  if (separateIssue) {
    val issueAdvance = issueEntries.zip(io.toAMU).map { case (entry, port) => issuedOrSkipped(entry) || port.fire }
    val issueCount = PriorityEncoder(issueAdvance.map(!_) :+ true.B)
    val nextIssuePtr = issuePtr + Mux(!issueBarrier && !io.redirect.valid, issueCount, 0.U)
    // Scalar entries can retire while a barrier freezes EVU dispatch. Never
    // leave the issue cursor behind entries that retirement has invalidated.
    issuePtr := Mux(isAfter(nextDeqPtr, nextIssuePtr), nextDeqPtr, nextIssuePtr)
    // Re-scan surviving entries after redirect. Accepted requests are skipped,
    // never re-issued; retirement stays the sole completion/status owner.
    when(io.redirect.valid) { issuePtr := nextDeqPtr }
    XSPerfAccumulate("evu_amu_issue_window_blocked", issueEntries.head.valid &&
      issueEntries.head.committed && !issueBarrier && !io.toAMU.map(_.fire).reduce(_ || _) &&
      issueEntries.map(_.amuReqValid).reduce(_ || _))
    XSPerfAccumulate("evu_amu_issue_ahead_cycles", issuePtr =/= deqPtr && issuedOutstanding)
    XSPerfAccumulate("evu_amu_barrier_cycles", issueBarrier)
  }
  // Update deqPtr and invalidate entries
  deqPtr := nextDeqPtr
  for (i <- 0 until CommitWidth) {
    val curr_deq_ptr = deqPtr + i.U
    when(amuCtrlEntries(curr_deq_ptr.value).canDeq && i.U < deqCount) {
      amuCtrlEntries(curr_deq_ptr.value) := AmuCtrlEntry.zero
    }
  }

  XSPerfAccumulate("stall_by_matrix_fire", !io.canEnqueue && deqPtr =/= io.deqCommit(0).ptr)
}
