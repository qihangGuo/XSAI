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
import xiangshan.backend.fu.matrix.Bundles._
import xiangshan.evu.{EvuCompletion, EvuCoreCommand}

class AmuCtrlBufferIO()(implicit val p: Parameters, val params: BackendParams) extends Bundle with HasXSParameter {
  // rob enq
  class EnqBundle()(implicit val p: Parameters) extends Bundle with HasXSParameter {
    val valid = Bool()  // Req valid and canEnqueue
    val reqValid = Bool()  // Req valid
    val allocPtr = new RobPtr
    val needAMU = Bool()
    val pc = Option.when(env.EnableDifftest && HasMatrixExtension)(UInt(VAddrBits.W))
    val pcTransType = Option.when(env.EnableDifftest && HasMatrixExtension)(new AddrTransType)
  }
  val enq = Input(Vec(RenameWidth, new EnqBundle))
  val canEnqueue = Output(Bool())
  val canEnqueueForDispatch = Output(Bool())
  val evuBusy = Output(Bool())
  val evuCommittedBusy = Output(Bool())
  val evuPreflightBusy = Output(Bool())
  val evuRetired = Output(Valid(new EvuCoreCommand(p(ExternalVectorKey))))

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
  val evuCompletion = OptionWrapper(p(ExternalVectorKey).enable, Flipped(Decoupled(new EvuCompletion(p(ExternalVectorKey)))))
  // One-cycle, in-order status pulse for the CSR/ROB side. EVU commands are
  // dispatched after the scalar instruction has retired, so their flags
  // cannot use the ordinary EXU writeback slot.
  val evuStatus = OptionWrapper(p(ExternalVectorKey).enable,
    Output(Valid(new EvuCompletion(p(ExternalVectorKey)))))
  val evuDirtyVs = Output(Bool())

  // If we enable difftest, there will be hartid
  val hartId = Option.when(env.EnableDifftest && HasMatrixExtension)(Input(UInt(hartIdLen.W)))
}


class AmuCtrlEntry(implicit p: Parameters) extends XSBundle {
  val valid = Bool()
  val needAMU = Bool()
  val writebacked = Bool()
  val committed = Bool()
  val amuCtrl = new AmuCtrlIO
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
    for (i <- 0 until RenameWidth) {
      io.enq(i).valid := robImp.io.enq.req(i).valid && robImp.canEnqueue(i) && !robImp.io.redirect.valid
      io.enq(i).reqValid := robImp.io.enq.req(i).valid
      io.enq(i).allocPtr := robImp.allocatePtrVec(i)
      io.enq(i).needAMU := robImp.io.enq.req(i).bits.needAmuCtrl.get

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
  io.canEnqueue := canEnqueue
  io.canEnqueueForDispatch := canEnqueueForDispatch

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
  val amuCtrlWb = io.wb.filter(_.bits.amuCtrl.nonEmpty).toSeq
  for (i <- 0 until RobSize) {
    val amu_data = amuCtrlWb.map{ wb =>
      val valid_match = wb.valid && wb.bits.robIdx.value === i.U
      Mux(valid_match, wb.bits.amuCtrl.get.asUInt, 0.U)
    }.reduce(_ | _)

    val entry = amuCtrlEntries(i)
    assert(!amu_data.orR || entry.valid, s"AMUCtrl entry $i is invalid but has amu_data")
    when (amu_data.orR) {
      entry.writebacked := true.B
      entry.amuCtrl := amu_data.asTypeOf(new AmuCtrlIO)
      // Set the pc field in amuCtrl when difftest is enabled
      if (env.EnableDifftest && HasMatrixExtension) {
        entry.amuCtrl.pc.get := dt_pc.get(i)
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
  io.evuRetired.valid := retiringEvu.reduce(_ || _)
  io.evuRetired.bits := PriorityMux(retiringEvu.zip(io.deqCommit.map { deq =>
    amuCtrlEntries(deq.ptr.value).amuCtrl.data.asTypeOf(new EvuCoreCommand(p(ExternalVectorKey)))
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

  io.toAMU.zipWithIndex.foreach { case (amuCtrl, i) =>
    val deqEntry = issueEntries(i)
    val olderAmuReqValid = if (i == 0) false.B else issueEntries.take(i).map(_.amuReqValid).reduce(_ || _)
    val olderIssued = issueEntries.take(i).map(issuedOrSkipped).foldLeft(true.B)(_ && _)
    deqEntry.checkSanity(s"AMUCtrlBuffer: deqEntry[$i]")
    amuCtrl.valid := deqEntry.amuReqValid && !io.redirect.valid &&
      (if (separateIssue) olderIssued && !issueBarrier else true.B)
    amuCtrl.bits := Mux(deqEntry.amuReqValid, deqEntry.amuCtrl, 0.U.asTypeOf(new AmuCtrlIO))
    when (amuCtrl.fire) {
      // XSCore serializes these lanes through a fixed-priority arbiter.
      assert(!olderAmuReqValid, s"AMUCtrlBuffer: toAMU[$i] fired before an older AMU request")
      assert(!deqEntry.canDeq, s"AMUCtrlBuffer: deqEntry[$i] is already set to canDeq")
      when (amuCtrl.bits.evu) {
        deqEntry.evuAccepted := true.B
        val f7 = amuCtrl.bits.data.asTypeOf(new EvuCoreCommand(p(ExternalVectorKey))).instr(31, 25)
        when(separateIssue.B && (f7 === xiangshan.evu.EvuIsa.waitOp || f7 === xiangshan.evu.EvuIsa.fence)) {
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

    // Difftest
    if (env.EnableDifftest && HasMatrixExtension) {
      val difftestPC = dt_pc.get((dispatchPtr + i.U).value)
      val difftestAmuCtrl = DifftestModule(new DiffAmuCtrlEvent, delay = 3, dontCare = true)
      difftestAmuCtrl.valid  := amuCtrl.fire && !amuCtrl.bits.evu
      difftestAmuCtrl.op     := amuCtrl.bits.op
      val pcTransType = dt_pcTransType.get((dispatchPtr + i.U).value)
      difftestAmuCtrl.pc     := Mux(pcTransType.shouldBeSext, SignExt(difftestPC, XLEN), difftestPC)
      difftestAmuCtrl.coreid := io.hartId.get
      difftestAmuCtrl.index  := i.U
      when (amuCtrl.bits.isMma()) {
        difftestAmuCtrl.rm       := mmaio.rm
        difftestAmuCtrl.md       := mmaio.md
        difftestAmuCtrl.sat      := mmaio.sat
        difftestAmuCtrl.ms1      := mmaio.ms1
        difftestAmuCtrl.ms2      := mmaio.ms2
        difftestAmuCtrl.mtilem   := mmaio.mtilem
        difftestAmuCtrl.mtilen   := mmaio.mtilen
        difftestAmuCtrl.mtilek   := mmaio.mtilek
        difftestAmuCtrl.types1   := mmaio.types1
        difftestAmuCtrl.types2   := mmaio.types2
        difftestAmuCtrl.typed    := mmaio.typed
        difftestAmuCtrl.isfp     := mmaio.isfp
      }
      when (amuCtrl.bits.isMls()) {
        difftestAmuCtrl.ms        := mlsio.ms
        difftestAmuCtrl.ls        := mlsio.ls
        difftestAmuCtrl.transpose := mlsio.transpose
        difftestAmuCtrl.types1    := mlsio.isacc
        difftestAmuCtrl.types2    := mlsio.isA
        difftestAmuCtrl.base      := mlsio.baseAddr
        difftestAmuCtrl.stride    := mlsio.stride
        difftestAmuCtrl.row       := mlsio.row
        difftestAmuCtrl.column    := mlsio.column
        difftestAmuCtrl.widths    := mlsio.widths
      }
      when (amuCtrl.bits.isRelease()) {
        difftestAmuCtrl.msyncRd := mreleaseio.msyncRd
      }
      when (amuCtrl.bits.isArith()) {
        difftestAmuCtrl.ms     := arithio.md
        difftestAmuCtrl.opType := arithio.opType
      }
    }
  }

  // EVU completion is consumed only by the oldest AMU entry; later vector commands must not bypass an older memory operation.
  io.evuCompletion.foreach { completion =>
    val headEvuAccepted = amuCtrlEntries(deqPtr.value).evuAccepted
    // canDeq is registered, so the completed head remains visible until the
    // following cycle's dequeue. Do not consume the next completion for it.
    completion.ready := headEvuAccepted && !amuCtrlEntries(deqPtr.value).canDeq && !io.redirect.valid
    when (completion.fire) {
      amuCtrlEntries(deqPtr.value).canDeq := true.B
      amuCtrlEntries(deqPtr.value).evuException := completion.bits.exception
      amuCtrlEntries(deqPtr.value).evuFflags := completion.bits.fflags
      amuCtrlEntries(deqPtr.value).evuVxsat := completion.bits.vxsat
      amuCtrlEntries(deqPtr.value).evuCause := completion.bits.cause
      evuStatusReg := completion.bits
      evuStatusPulse := true.B
    }
    if (env.EnableDifftest) {
      val event = DifftestModule(new DiffEvuCompletionEvent, delay = 3)
      val entry = amuCtrlEntries(deqPtr.value)
      val payload = entry.amuCtrl.data.asTypeOf(new EvuCoreCommand(p(ExternalVectorKey)))
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

  // A retired WAIT/FENCE must keep dispatch blocked through the ROB ->
  // backend -> CSR status pipeline as well as through the final write Ack.
  io.evuBusy := amuCtrlEntries.map(e =>
    e.valid && e.needAMU && e.writebacked && e.amuCtrl.evu && !e.canDeq
  ).reduce(_ || _) || statusDrain.orR
  // A faulting scalar EVU instruction has not committed and must never wait
  // for its own nonexistent external completion before taking the trap.
  io.evuCommittedBusy := amuCtrlEntries.map(e =>
    e.valid && e.needAMU && e.committed && e.writebacked && e.amuCtrl.evu && !e.canDeq
  ).reduce(_ || _) || statusDrain.orR || evuStatusPulse
  // Preflight may walk pages beside older ordinary memory or arithmetic. Keep it behind
  // older control commands and the status pipeline, not ordinary memory.
  // The VMU now owns per-command translations, ordering and asynchronous faults.
  io.evuPreflightBusy := amuCtrlEntries.map { e =>
    val f7 = e.amuCtrl.data.asTypeOf(new EvuCoreCommand(p(ExternalVectorKey))).instr(31, 25)
    val serial = f7 === xiangshan.evu.EvuIsa.launch || f7 === xiangshan.evu.EvuIsa.waitOp ||
      f7 === xiangshan.evu.EvuIsa.fence || f7 === xiangshan.evu.EvuIsa.setvli ||
      f7 === xiangshan.evu.EvuIsa.setvl
    e.valid && e.needAMU && e.writebacked && e.amuCtrl.evu && !e.canDeq && serial
  }.reduce(_ || _) || statusDrain.orR

  val deqCondSeq = deqEntries.map(x => ~x.canDeq)
  val deqCount = PriorityEncoder(deqCondSeq :+ true.B)
  if (separateIssue) {
    val issueAdvance = issueEntries.zip(io.toAMU).map { case (entry, port) => issuedOrSkipped(entry) || port.fire }
    val issueCount = PriorityEncoder(issueAdvance.map(!_) :+ true.B)
    val nextDeqPtr = deqPtr + deqCount
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
  deqPtr := deqPtr + deqCount
  for (i <- 0 until CommitWidth) {
    val curr_deq_ptr = deqPtr + i.U
    when(amuCtrlEntries(curr_deq_ptr.value).canDeq && i.U < deqCount) {
      amuCtrlEntries(curr_deq_ptr.value) := AmuCtrlEntry.zero
    }
  }

  XSPerfAccumulate("stall_by_matrix_fire", !io.canEnqueue && deqPtr =/= io.deqCommit(0).ptr)
}
