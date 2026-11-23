package xiangshan.evu.backend

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tile._
import freechips.rocketchip.util._
import xiangshan.evu.mem._
import xiangshan.evu.exu._
import xiangshan.evu.common._
import xiangshan.evu.insns._

class VectorBackend(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val dis = Flipped(Decoupled(new VectorIssueInst))

    val vmu = Flipped(new VectorMemDatapathIO)

    val busy = Output(Bool())
    val debugVrf = Option.when(vParams.debugVrf)(Output(Vec(egsTotal, UInt(dLen.W))))
    val vrfPerf = Output(new VrfPerf)
    val executionPerf = Output(new EvuExecutionPerf)

    val index_access = new VectorIndexAccessIO
    val mask_access = new VectorMaskAccessIO

    val scalar_resp = Decoupled(new ScalarWrite)

    val set_vxsat = Output(Bool())
    val set_fflags = Output(Valid(UInt(5.W)))

    val fp_req = Decoupled(new FPInput())
    val fp_resp = Flipped(Valid(new FPResult()))

    val vat_tail = Input(UInt(vParams.vatSz.W))
    val vat_head = Input(UInt(vParams.vatSz.W))
    val reportHead = Option.when(vParams.externalCommandTags && vParams.diagnosticCounters)(
      Input(Valid(UInt(vParams.vatSz.W))))

    val vat_release = Output(Vec(nRelease, Valid(UInt(vParams.vatSz.W))))
    val completion_vxsat = Output(Vec(nRelease, Bool()))
    val completion_fflags = Output(Vec(nRelease, UInt(5.W)))

  })

  require(vLen >= 64)
  require(xLen == 64)
  require(vLen >= dLen)
  require(vLen % dLen == 0)

  def vatOlder(i0: UInt, i1: UInt) = cqOlder(i0, i1, io.vat_tail)

  // ====================================================================
  // Set up the dispatch queue, issue queues, sequencers, execution units

  val vdq = Module(new DCEQueue(new VectorIssueInst, vParams.vdqEntries))
  vdq.io.enq <> io.dis

  val xissParams = vParams.issStructure.generate(vParams)
  val all_supported_insns = xissParams.map(_.insns).flatten
  val interleave = vParams.interleaveReductions && vParams.reductionContexts > 1
  val contextLayouts = xissParams.map(_.seqs.map { s =>
    ExecuteContextLayout(s.name, interleave, vParams.fmaOrdinaryContexts, vParams.reductionContexts)
  })

  val vlissq = Module(new IssueQueue(vParams.vlissqEntries, 1))
  val vsissq = Module(new IssueQueue(vParams.vsissqEntries, 1))
  val vpissq = Module(new IssueQueue(vParams.vpissqEntries, vParams.reductionContexts))
  val vxissqs = xissParams.zip(contextLayouts).map { case (q, layouts) =>
    Module(new IssueQueue(q.depth, layouts.map(_.size).sum)).suggestName(s"vxissq_${q.name}")
  }

  val vxus = xissParams.map(_.seqs.map(s => Module(new ExecutionUnit(s.fus, s.name)).suggestName(s"vxu${s.name}")))
  val flat_vxus = vxus.flatten
  val forwardingNames = Set("fp_fma", "fp_cmp", "fp_exp") ++
    (if (vParams.extendedResultForwarding) Set("int", "fp_div", "fp_conv", "fp_xx8", "fp_mxfp", "fp_bf16") else Set.empty[String])
  val maxPipeDepth = flat_vxus.map(_.maxPipeDepth).max

  val vls = Module(new LoadSequencer)
  val vss = Module(new StoreSequencer(resultForwarding = true))
  val reductionSeqs = (0 until vParams.reductionContexts).map { i =>
    Module(new SpecialSequencer(all_supported_insns, i, vParams.reductionContexts, interleave)).suggestName(s"vps$i")
  }
  val vps = reductionSeqs.head // Only context zero handles permutations and VMU index/mask streams.
  val executionContexts = xissParams.zip(contextLayouts).map { case (q, layouts) =>
    q.seqs.zip(layouts).map { case (s, layout) =>
      (0 until layout.size).map { c =>
        Module(new ExecuteSequencer(s.insns, maxPipeDepth, s.fus.size,
          ordinaryOnly = layout.dedicated && layout.reductionOwner(c).isEmpty,
          reductionOnly = layout.reductionOwner(c).nonEmpty,
          resultForwarding = forwardingNames.contains(s.name),
          restrictedInteger = s.name == "int" && c > 0))
          .suggestName(s"vxs${s.name}_$c")
      }
    }
  }
  val vxs = executionContexts.map(_.flatten)

  val allSeqs = Seq(vls, vss) ++ reductionSeqs ++ vxs.flatten
  val allIssQs = Seq(vlissq, vsissq, vpissq) ++ vxissqs

  val flat_vxs = vxs.flatten
  val contextsByFu = executionContexts.flatten
  val reductionOwnerBySequencer = contextsByFu.zip(contextLayouts.flatten).flatMap { case (seqs, layout) =>
    seqs.zipWithIndex.map { case (seq, c) => seq -> layout.reductionOwner(c) }
  }.toMap
  val fuBySequencer = contextsByFu.zip(flat_vxus).flatMap { case (seqs, xu) => seqs.map(_ => xu) }
  val representativeByFu = contextsByFu.map(seqs => flat_vxs.indexOf(seqs.head))
  io.executionPerf := 0.U.asTypeOf(io.executionPerf)
  def perfCount(condition: Bool): UInt = {
    val counter = RegInit(0.U(64.W))
    when(condition) { counter := counter + 1.U }
    counter
  }
  def perfSum(increment: UInt): UInt = {
    val counter = RegInit(0.U(64.W))
    counter := counter + increment
    counter
  }
  def diagnosticCount(name: String, increment: UInt): UInt = {
    if (vParams.diagnosticCounters) {
      val counter = RegInit(0.U(64.W)).suggestName(name)
      counter := counter + increment
      counter
    } else 0.U(64.W)
  }
  val pendingWrites = Wire(Vec(vParams.vrfBanking, Valid(new VectorWriteHazard)))
  require(contextsByFu.size == flat_vxus.size)

  io.fp_req.valid := false.B
  io.fp_req.bits := DontCare
  vxus.foreach(_.foreach(_.io.shared_fp_req := DontCare))
  vxus.foreach(_.foreach(_.io.shared_fp_resp := DontCare))

  val shared_fp_vxu = vxus.flatten.filter(_.hasSharedFPUnits)
  require(shared_fp_vxu.size <= 1)
  shared_fp_vxu.headOption.foreach { vxu =>
    io.fp_req <> vxu.io.shared_fp_req
    vxu.io.shared_fp_resp <> io.fp_resp
  }

  case class IssueGroup(
    issq: IssueQueue,
    seqs: Seq[Sequencer[_]])


  val issGroups = Seq(
    IssueGroup(vlissq, Seq(vls)),
    IssueGroup(vsissq, Seq(vss)),
    IssueGroup(vpissq, reductionSeqs),
  ) ++ (vxissqs.zip(vxs).map { case (q, seqs) =>
    IssueGroup(q, seqs)
  })

  // ======================================
  // Set inputs to each issq/sequencer pair

  // Set common defaults
  for (issq <- allIssQs) {
    issq.io.enq.bits.reduction := false.B
    issq.io.enq.bits.wide_vd := false.B
    issq.io.enq.bits.wide_vs2 := false.B
    issq.io.enq.bits.writes_mask := false.B
    issq.io.enq.bits.reads_vs1_mask := false.B
    issq.io.enq.bits.reads_vs2_mask := false.B
    issq.io.enq.bits.nf_log2 := 0.U
    issq.io.enq.bits.renv1 := false.B
    issq.io.enq.bits.renv2 := false.B
    issq.io.enq.bits.renvd := false.B
    issq.io.enq.bits.renvm := false.B
    issq.io.enq.bits.wvd   := false.B
    issq.io.enq.bits.scalar_to_vd0 := false.B
    issq.io.enq.bits.rs1_is_rs2 := false.B
  }

  val dis_ctrl = Wire(new VectorDecodedControl(all_supported_insns, Seq(
    Reduction, Wide2VD, Wide2VS2, WritesAsMask,
    ReadsVS1AsMask, ReadsVS2AsMask, ReadsVS1, ReadsVS2, ReadsVD,
    VMBitReadsVM, AlwaysReadsVM, WritesVD, WritesScalar, ScalarToVD0
  ))).decode(vdq.io.deq.bits)
  // Allocate a shared owner before either half of a reduction enters its
  // queue. VAT parity cannot select contexts: alternating sum/max commands
  // would otherwise pin every sum to the same context.
  val reductionOwners = Module(new ReductionOwnerAllocator(vParams.reductionContexts))
  val reductionChoice = reductionOwners.io.choice
  val dispatchReduction = !vdq.io.deq.bits.vmu && dis_ctrl.bool(Reduction)
  val dispatchDirectMinMax = dispatchReduction && VectorFastPaths.singleGroupMinMaxInstruction(vdq.io.deq.bits,
    dLen, vParams.fp32MinMaxTree && vParams.singleGroupMinMax)
  val dispatchNeedsAccumulator = dispatchReduction && !dispatchDirectMinMax
  reductionOwners.io.request := interleave.B && vdq.io.deq.valid && dispatchNeedsAccumulator
  reductionOwners.io.dispatch := vdq.io.deq.fire
  reductionOwners.io.release := VecInit(reductionSeqs.map(s => s.io.acc_done && s.io.busy)).asUInt
  val reductionBlocked = reductionOwners.io.blocked
  io.executionPerf.reductionFull := perfCount(vdq.io.deq.valid && reductionBlocked)
  io.executionPerf.reductionReleaseFull := diagnosticCount("diag_reductionReleaseFull", reductionOwners.io.fullWithRelease)
  io.executionPerf.reductionOwnerReuse := diagnosticCount("diag_reductionOwnerReuse", reductionOwners.io.reused)

  // Load sequencer
  vlissq.io.enq.bits.nf_log2 := log2_up(vdq.io.deq.bits.nf, 8)
  vlissq.io.enq.bits.renvm := !vdq.io.deq.bits.vm
  vlissq.io.enq.bits.wvd   := true.B

  // Store sequencer
  vsissq.io.enq.bits.nf_log2 := log2_up(vdq.io.deq.bits.nf, 8)
  vsissq.io.enq.bits.renvd := true.B
  vsissq.io.enq.bits.renvm := !vdq.io.deq.bits.vm && vdq.io.deq.bits.mop === mopUnit

  // Permute source sequencer
  vpissq.io.enq.bits.renv1 := !vdq.io.deq.bits.vmu && dis_ctrl.bool(Reduction)
  vpissq.io.enq.bits.renv2 := (vdq.io.deq.bits.mop(0) || (!vdq.io.deq.bits.vmu && !dis_ctrl.bool(Reduction)))
  vpissq.io.enq.bits.renvd := !dis_ctrl.bool(Reduction)
  vpissq.io.enq.bits.renvm := !vdq.io.deq.bits.vm && vdq.io.deq.bits.mop =/= mopUnit && vdq.io.deq.bits.vmu
  vpissq.io.enq.bits.wide_vd := dis_ctrl.bool(Wide2VD) && !vdq.io.deq.bits.vmu
  vpissq.io.enq.bits.rs1_is_rs2 := !vdq.io.deq.bits.vmu && (vdq.io.deq.bits.opif6 === OPIFunct6.rgather || (vdq.io.deq.bits.funct3 === OPIVV && vdq.io.deq.bits.opif6 === OPIFunct6.rgatherei16))

  // Execute sequencers
  vxissqs.foreach { vxissq =>
    vxissq.io.enq.bits.wide_vd := dis_ctrl.bool(Wide2VD)
    vxissq.io.enq.bits.wide_vs2 := dis_ctrl.bool(Wide2VS2)
    vxissq.io.enq.bits.writes_mask := dis_ctrl.bool(WritesAsMask)
    vxissq.io.enq.bits.reads_vs1_mask := dis_ctrl.bool(ReadsVS1AsMask)
    vxissq.io.enq.bits.reads_vs2_mask := dis_ctrl.bool(ReadsVS2AsMask)
    vxissq.io.enq.bits.renv1 := (dis_ctrl.bool(ReadsVS1) && !dis_ctrl.bool(Reduction)) || dispatchDirectMinMax
    vxissq.io.enq.bits.renv2 := dis_ctrl.bool(ReadsVS2) ||
      VectorFastPaths.alignedSlide(vdq.io.deq.bits, dLen, vParams.alignedSlide) ||
      VectorFastPaths.singleGroupSlide(vdq.io.deq.bits, dLen, vParams.singleGroupSlide)
    vxissq.io.enq.bits.renvd := dis_ctrl.bool(ReadsVD)
    vxissq.io.enq.bits.renvm := (!vdq.io.deq.bits.vm && dis_ctrl.bool(VMBitReadsVM)) || dis_ctrl.bool(AlwaysReadsVM)
    vxissq.io.enq.bits.wvd := !dis_ctrl.bool(WritesScalar)
    vxissq.io.enq.bits.scalar_to_vd0 := dis_ctrl.bool(ScalarToVD0)
    vxissq.io.enq.bits.reduction := dis_ctrl.bool(Reduction)
  }

  // ======================================
  // Connect VDQ to issue queues
  // Connect issue queues to sequencers

  val issq_stall = Wire(Vec(issGroups.size, Bool()))
  vdq.io.deq.ready := !issq_stall.orR && !reductionBlocked
  io.executionPerf.dispatchBlocked := diagnosticCount("diag_dispatchBlocked",
    vdq.io.deq.valid && !vdq.io.deq.ready)
  val dispatchNames = Seq("load", "store", "special") ++ xissParams.map(_.name)
  for ((name, index) <- dispatchNames.zipWithIndex if EvuDispatchPerf.names.contains(name)) {
    io.executionPerf.dispatchQueueWait(EvuDispatchPerf.names.indexOf(name)) :=
      diagnosticCount(s"diag_dispatch_${name}_queueWait", vdq.io.deq.valid && issq_stall(index))
  }

  var flat_vxu_id: Int = 0

  for ((group, i) <- issGroups.zipWithIndex) {
    val otherIssGroups = issGroups.zipWithIndex.filter(_._2 != i).map(_._1)
    val otherIssqs = otherIssGroups.map(_.issq)
    val otherIssqSeqs = otherIssGroups.map(_.seqs).flatten

    for ((seq, j) <- group.seqs.zipWithIndex) {
      val otherSameIssqSeqs = group.seqs.zipWithIndex.filter(_._2 != j).map(_._1)
      val otherSeqs = otherIssqSeqs ++ otherSameIssqSeqs

      val vat = seq.io.vat

      seq.io.vat_head := io.vat_head

      val older_issq_wintents = FillInterleaved(egsPerVReg, otherIssqs.map { i =>
        i.io.hazards.map(h => Mux(vatOlder(h.bits.vat, vat) && h.valid, h.bits.wintent, 0.U))
      }.flatten.foldLeft(0.U)(_|_))
      val older_seq_wintents = otherSeqs.map { s =>
        Mux(vatOlder(s.io.seq_hazard.bits.vat, vat) && s.io.seq_hazard.valid, s.io.seq_hazard.bits.wintent, 0.U)
      }.reduce(_|_)
      val older_wintents = older_issq_wintents | older_seq_wintents

      val older_issq_rintents = FillInterleaved(egsPerVReg, otherIssqs.map { i =>
        i.io.hazards.map(h => Mux(vatOlder(h.bits.vat, vat) && h.valid, h.bits.rintent, 0.U))
      }.flatten.foldLeft(0.U)(_|_))
      val older_seq_rintents = otherSeqs.map { s =>
        Mux(vatOlder(s.io.seq_hazard.bits.vat, vat) && s.io.seq_hazard.valid, s.io.seq_hazard.bits.rintent, 0.U)
      }.reduce(_|_)
      val older_rintents = older_issq_rintents | older_seq_rintents

      val (other_vxus, same_vxu): (Seq[ExecutionUnit], Option[ExecutionUnit]) = seq match {
        case s: ExecuteSequencer => {
          val same_vxu = fuBySequencer(flat_vxu_id)
          val other_vxus = flat_vxus.filterNot(_ == same_vxu)
          flat_vxu_id += 1
          (other_vxus, Some(same_vxu))
        }
        case _ => (flat_vxus, None)
      }

      // Older writes from adjacent VXUs will always induce WAW/RAW, but older
      // writes from the same VXU may be from the same instruction, and no WAR
      // or RAW is possible
      val older_other_pipe_writes = other_vxus.map(_.io.pipe_hazards.toSeq).flatten.map { h =>
        Mux(h.valid, h.bits.eg_oh, 0.U)
      }.foldLeft(0.U)(_|_)
      val older_same_pipe_writes = same_vxu.map(_.io.pipe_hazards.toSeq.map { h =>
        Mux(h.valid && h.bits.vat =/= vat, h.bits.eg_oh, 0.U)
      }.foldLeft(0.U)(_|_)).getOrElse(0.U)
      val older_pipe_writes = older_other_pipe_writes | older_same_pipe_writes

      val older_iter_writes = flat_vxus.map(_.io.iter_hazards.toSeq).flatten.map { h =>
        Mux(h.valid, h.bits.eg_oh, 0.U)
      }.reduce(_|_)

      val older_buffered_writes = pendingWrites.map { h =>
        Mux(h.valid && vatOlder(h.bits.vat, vat), h.bits.eg_oh, 0.U)
      }.reduce(_ | _)

      seq.io.older_writes := older_pipe_writes | older_iter_writes | older_buffered_writes | older_wintents
      seq.io.older_reads := older_rintents

      if (!vParams.enableOOO) {
        // stall dispatch if any other sequencers are at the head and stalled
        seq.io.dis_stall := otherSeqs.map { s =>
          s.io.busy && s.io.head && !(s.io.iss.valid && s.io.iss.ready)
        }.orR
      } else {
        seq.io.dis_stall := false.B // never stall dispatch
      }
    }

    val accepts = group.seqs.zipWithIndex.map { case (seq, c) =>
      val contextAllowed = if (!interleave) true.B else if (group.issq == vpissq) {
        !dispatchNeedsAccumulator || reductionChoice(c)
      } else seq match {
        case s: ExecuteSequencer if s.reductionOnly =>
          dispatchDirectMinMax || reductionChoice(reductionOwnerBySequencer(s).get)
        case _ => true.B
      }
      seq.accepts(vdq.io.deq.bits) && contextAllowed
    }
    issq_stall(i) := !group.issq.io.enq.ready && accepts.orR

    group.issq.io.enq.valid := vdq.io.deq.valid && !issq_stall.orR && !reductionBlocked && accepts.orR
    group.issq.io.enq.bits.viewAsSupertype(new VectorIssueInst) := vdq.io.deq.bits
    group.issq.io.enq.bits.seq := VecInit(accepts).asUInt

    // In case of multiple available sequencers, select the first ready one
    val valid_seqs = group.issq.io.deq.bits.seq
    val ready_seqs = VecInit(group.seqs.map(_.io.dis.ready)).asUInt
    val chosen_seq = PriorityEncoderOH(valid_seqs & ready_seqs)

    group.seqs.zipWithIndex.foreach { case(s, j) =>
      s.io.dis.valid := group.issq.io.deq.valid && chosen_seq(j)
      s.io.dis.bits := group.issq.io.deq.bits.viewAsSupertype(new BackendIssueInst)
    }
    group.issq.io.deq.ready := (valid_seqs & ready_seqs) =/= 0.U
  }
  when(vdq.io.deq.fire && dispatchDirectMinMax) {
    assert(!reductionOwners.io.request && !vpissq.io.enq.fire,
      "Single-EG min/max must not allocate an accumulator owner or seed task")
  }

  // ======================================
  // Connect reads to VRF

  val vrf = Module(new RegisterAccess(flat_vxs.size, maxPipeDepth,
    reductionContexts = vParams.reductionContexts,
    shortIntegerContexts = flat_vxs.zipWithIndex.collect { case (seq, i) if seq.restrictedInteger => i }.toSet,
    executionClasses = contextsByFu.zip(xissParams.flatMap(_.seqs)).flatMap { case (seqs, factory) =>
      Seq.fill(seqs.size)(EvuReadClass.names.indexOf(factory.name) match { case -1 => 4; case id => id })
    }))
  pendingWrites := vrf.io.pendingWrites
  io.debugVrf.foreach(_ := vrf.io.debug.get)
  io.vrfPerf := vrf.io.perf

  val forwardSeqs = flat_vxs.filter(_.io.forward.nonEmpty)
  val forwardUnits = flat_vxus.zip(xissParams.flatMap(_.seqs)).collect {
    case (unit, factory) if forwardingNames.contains(factory.name) && unit.pipe_fus.nonEmpty => unit
  }
  val forwardIterUnits = flat_vxus.zip(xissParams.flatMap(_.seqs)).collect {
    case (unit, factory) if vParams.extendedResultForwarding && forwardingNames.contains(factory.name) &&
      unit.iter_fus.nonEmpty => unit
  }
  val forwardWriters = scala.collection.mutable.ArrayBuffer.empty[ValidIO[VectorForwardWriter]]
  def forwardWriter(valid: Bool, vat: UInt, writes: UInt): Unit = {
    val writer = Wire(Valid(new VectorForwardWriter))
    writer.valid := valid
    writer.bits.vat := vat
    writer.bits.writes := writes
    forwardWriters += writer
  }
  for (queue <- allIssQs; hazard <- queue.io.hazards) {
    forwardWriter(hazard.valid, hazard.bits.vat, FillInterleaved(egsPerVReg, hazard.bits.wintent))
  }
  for (seq <- allSeqs) {
    forwardWriter(seq.io.seq_hazard.valid, seq.io.seq_hazard.bits.vat, seq.io.seq_hazard.bits.wintent)
  }
  for (unit <- flat_vxus; hazard <- unit.io.pipe_hazards.toSeq ++ unit.io.iter_hazards.toSeq) {
    forwardWriter(hazard.valid, hazard.bits.vat, hazard.bits.eg_oh)
  }
  for (hazard <- pendingWrites) { forwardWriter(hazard.valid, hazard.bits.vat, hazard.bits.eg_oh) }
  val forwarding = Module(new VectorResultForwarding(forwardUnits.size + forwardIterUnits.size, forwardSeqs.size * 3 + 1,
    forwardWriters.size, partial = vParams.shortResultForwarding,
    retainCycles = if (vParams.shortResultForwarding) 4 else 0,
    invalidationPorts = vParams.vrfBanking))
  forwarding.io.tail := io.vat_tail
  forwarding.io.writers := VecInit(forwardWriters.toSeq)
  forwarding.io.invalidate := vrf.io.writeInvalidations
  for ((unit, i) <- forwardUnits.zipWithIndex) {
    val result = forwarding.io.results(i)
    result.valid := unit.io.pipe_write.valid && unit.io.pipeWriteForwardable
    result.bits.eg := unit.io.pipe_write.bits.eg
    result.bits.data := unit.io.pipe_write.bits.data
    result.bits.mask := unit.io.pipe_write.bits.mask
    result.bits.vat := unit.io.pipeWriteVat
    forwarding.io.accepted.foreach(_(i) := unit.io.pipe_write.valid)
  }
  for ((unit, index) <- forwardIterUnits.zipWithIndex) {
    val result = forwarding.io.results(forwardUnits.size + index)
    result.valid := unit.io.iter_write.valid && unit.io.iterWriteForwardable
    result.bits.eg := unit.io.iter_write.bits.eg
    result.bits.data := unit.io.iter_write.bits.data
    result.bits.mask := unit.io.iter_write.bits.mask
    result.bits.vat := unit.io.iterWriteVat
    forwarding.io.accepted.foreach(_(forwardUnits.size + index) := unit.io.iter_write.fire)
  }
  val forwardReads = forwardSeqs.flatMap(seq => Seq(seq.io.rvs1, seq.io.rvs2, seq.io.rvd)
    .zip(seq.io.forward.get).map { case (read, port) => (read, port, seq.io.vat) })
  for (((read, port, vat), i) <- forwardReads.zipWithIndex) {
    forwarding.io.reads(i).valid := port.eligible
    forwarding.io.reads(i).bits.eg := read.req.bits.eg
    forwarding.io.reads(i).bits.vat := vat
    forwarding.io.reads(i).bits.mask := port.mask
    port.available := forwarding.io.out(i).valid
  }
  val storeForwardIndex = forwardReads.size
  forwarding.io.reads(storeForwardIndex).valid := vss.io.forward.get.eligible
  forwarding.io.reads(storeForwardIndex).bits.eg := vss.io.rvd.bits.eg
  forwarding.io.reads(storeForwardIndex).bits.vat := vss.io.vat
  forwarding.io.reads(storeForwardIndex).bits.mask := vss.io.forward.get.mask
  vss.io.forward.get.available := forwarding.io.out(storeForwardIndex).valid
  io.executionPerf.vectorForwards := diagnosticCount("vectorForwards", PopCount(VecInit(
    forwardReads.zipWithIndex.map { case ((read, _, _), i) => read.req.fire && forwarding.io.out(i).valid } :+
      (vss.io.rvd.fire && forwarding.io.out(storeForwardIndex).valid))))
  io.executionPerf.vectorForwardNewer := diagnosticCount("vectorForwardNewer",
    forwarding.io.newerWriter.asUInt.orR.asUInt)

  vrf.io.vls.rvm.req <> vls.io.rvm
  vrf.io.vss.rvd.req <> vss.io.rvd
  vrf.io.vss.rvd.req.valid := vss.io.rvd.valid && !forwarding.io.out(storeForwardIndex).valid
  vss.io.rvd.ready := vrf.io.vss.rvd.req.ready || forwarding.io.out(storeForwardIndex).valid
  vrf.io.vss.rvm.req <> vss.io.rvm
  vrf.io.vss.e0Read := vss.io.e0Read
  // A forwarding hit forces ready; do not feed collector ready back into its invalidation.
  vrf.io.vss.bypass := vss.io.rvd.valid && forwarding.io.out(storeForwardIndex).valid
  vss.io.rvd_resp := Mux(forwarding.io.out(storeForwardIndex).valid,
    forwarding.io.out(storeForwardIndex).bits, vrf.io.vss.rvd.resp)
  vss.io.rvm_resp := vrf.io.vss.rvm.resp
  for ((seq, i) <- reductionSeqs.zipWithIndex) {
    vrf.io.vps(i).rvs2.req <> seq.io.rvs2
    vrf.io.vps(i).rvm.req <> seq.io.rvm
    vrf.io.vps(i).e0Read := seq.io.e0Read
    seq.io.acc_init_resp := vrf.io.vps(i).rvs2.resp
    seq.io.rvm_resp := vrf.io.vps(i).rvm.resp
    if (i != 0) { seq.io.iss.ready := false.B }
  }

  val executeCandidates = scala.collection.mutable.ArrayBuffer.empty[DecoupledIO[ExecuteMicroOpWithData]]
  for (i <- 0 until flat_vxs.size) {
    val vxs = flat_vxs(i)
    val vxu = fuBySequencer(i)
    vrf.io.vxs(i).bypass := VecInit.fill(3)(false.B)

    vrf.io.vxs(i).rvs1.req <> vxs.io.rvs1.req
    vrf.io.vxs(i).rvs2.req <> vxs.io.rvs2.req
    vrf.io.vxs(i).rvd.req <> vxs.io.rvd.req
    vrf.io.vxs(i).rvm.req <> vxs.io.rvm.req
    vxs.io.rvs1.resp := vrf.io.vxs(i).rvs1.resp
    vxs.io.rvs2.resp := vrf.io.vxs(i).rvs2.resp
    vxs.io.rvd.resp := vrf.io.vxs(i).rvd.resp
    if (vxs.io.forward.nonEmpty) {
      val base = forwardSeqs.indexOf(vxs) * 3
      for (((read, memory), port) <- Seq((vxs.io.rvs1, vrf.io.vxs(i).rvs1),
        (vxs.io.rvs2, vrf.io.vxs(i).rvs2), (vxs.io.rvd, vrf.io.vxs(i).rvd)).zipWithIndex) {
        val result = forwarding.io.out(base + port)
        memory.req.valid := read.req.valid && !result.valid
        read.req.ready := result.valid || memory.req.ready
        read.resp := Mux(result.valid, result.bits, memory.resp)
        vrf.io.vxs(i).bypass(port) := read.req.valid && result.valid
      }
    }
    vxs.io.rvm.resp := vrf.io.vxs(i).rvm.resp
    vrf.io.vxs(i).prefetch := vxs.io.prefetch
    vrf.io.vxs(i).e0Read := vxs.io.e0Read

    val vxs_iss = vxs.io.iss.bits
    val candidate = Wire(Decoupled(new ExecuteMicroOpWithData(vxu.nFUs)))
    val vxu_iss = candidate.bits

    val rvs1_data = Mux1H(Seq(
      vxs_iss.use_scalar_rvs1 -> dLenSplat(vxs_iss.scalar, vxs_iss.rvs1_eew),
      vxs_iss.use_normal_rvs1 -> vxs.io.rvs1_data))
    val rvs2_data = Mux1H(Seq(
      vxs_iss.use_zero_rvs2 -> 0.U,
      vxs_iss.use_slide_rvs2 -> vxs_iss.slide_data,
      vxs_iss.use_normal_rvs2 -> vxs.io.rvs2_data))
    val rvd_data = vxs.io.rvd_data

    vxu_iss.rvs1_data := rvs1_data
    vxu_iss.rvs2_data := rvs2_data
    vxu_iss.rvd_data := rvd_data

    val rvs1_elem = extractElem(rvs1_data, vxs_iss.rvs1_eew, vxs_iss.eidx)
    val rvs2_elem = extractElem(rvs2_data, vxs_iss.rvs2_eew, vxs_iss.eidx)
    val rvd_elem = extractElem(rvd_data, vxs_iss.rvd_eew, vxs_iss.eidx)

    vxu_iss.rvs1_elem := rvs1_elem
    vxu_iss.rvs2_elem := rvs2_elem
    vxu_iss.rvd_elem := rvd_elem

    val vm_off    = ((1 << dLenOffBits) - 1).U(log2Ceil(dLen).W)
    val vm_eidx   = (vxs_iss.eidx & ~(vm_off >> vxs_iss.vd_eew))(log2Ceil(dLen)-1,0)
    val vm_resp   = (vxs.io.rvm_data >> vm_eidx)(dLenB-1,0)
    val vm_mask   = Mux(vxs_iss.use_wmask,
      VecInit.tabulate(3)({ sew => FillInterleaved(1 << sew, vm_resp)(dLenB-1,0) })(vxs_iss.vd_eew),
      ~(0.U(dLenB.W))
    )
    vxu_iss.wmask := vxs_iss.eidx_mask & vm_mask
    vxu_iss.rmask := Mux(vxs_iss.vm, ~(0.U(dLenB.W)), vm_resp)
    vxu_iss.rvm_data := Mux(vxs_iss.vm, ~(0.U(dLen.W)), vxs.io.rvm_data)

    candidate.valid := vxs.io.iss.valid
    vxs.io.iss.ready := candidate.ready
    candidate.bits.viewAsSupertype(new ExecuteMicroOp(vxu.nFUs)) := vxs.io.iss.bits
    executeCandidates += candidate

    when (vxs_iss.acc_direct) {
      // Direct reductions collect their own seed; no SpecialSequencer owns this instruction.
      vxu_iss.wmask := eewByteMask(vxs_iss.vd_eew)
      vxu_iss.rvs1_elem := rvs1_data(31, 0)
      vxu_iss.rvs1_data := rvs1_data(31, 0)
      vxu_iss.rvs1_eew := vxs_iss.vd_eew
    } .elsewhen (vxs_iss.acc) {
      val owners = reductionSeqs.map(s => s.io.busy && s.io.vat === vxs.io.vat)
      val acc_data = Mux1H(owners, reductionSeqs.map(_.io.acc_data.bits))
      val acc_other_data = Mux1H(owners, reductionSeqs.map(_.io.acc_other_data))
      val acc_init = Mux1H(owners, reductionSeqs.map(_.io.acc_init))
      when (vxs_iss.acc_merge) {
        vxu_iss.wmask := ~(0.U(dLenB.W))
        vxu_iss.rvs1_elem := acc_data
        vxu_iss.rvs1_data := acc_data
        vxu_iss.rvs1_eew := vxs_iss.vd_eew
        vxu_iss.rvs2_elem := acc_other_data
        vxu_iss.rvs2_data := acc_other_data
        vxu_iss.rvs2_eew := vxs_iss.vd_eew
      } .elsewhen (vxs_iss.acc_fold) {
        val acc_fold_id = vxs_iss.acc_fold_id
        val folded = VecInit.tabulate(log2Ceil(dLenB))(i => acc_data((dLen >> i) - 1, dLen >> (i + 1)))(acc_fold_id)
        // Only the lower half of the remaining lanes participates in a fold.
        // Computing the discarded lanes can otherwise raise spurious flags
        // (e.g. an inactive tail sNaN retained in an upper accumulator lane).
        val foldMask = VecInit.tabulate(log2Ceil(dLenB))(i =>
          ((BigInt(1) << (dLenB >> (i + 1))) - 1).U(dLenB.W))(acc_fold_id)
        vxu_iss.wmask := Mux(vxs_iss.acc_copy || vxs_iss.acc_tree || vxs_iss.acc_empty,
          eewByteMask(vxs_iss.vd_eew), foldMask)
        vxu_iss.rvs1_elem := Mux(vxs_iss.acc_copy, acc_init, folded)
        vxu_iss.rvs1_data := Mux(vxs_iss.acc_copy, acc_init, folded)
        vxu_iss.rvs1_eew := vxs_iss.vd_eew
        vxu_iss.rvs2_elem := acc_data
        vxu_iss.rvs2_data := acc_data
        vxu_iss.rvs2_eew  := vxs_iss.vd_eew
      } .otherwise {
        when (vxs_iss.acc_ew) {
          val mask_bit = Mux(vxs_iss.use_wmask, (vxs.io.rvm_data >> vxs_iss.eidx(log2Ceil(dLen)-1,0))(0), true.B)
          vxu_iss.wmask := Mux(mask_bit, eewByteMask(vxs_iss.vd_eew), 0.U)
          vxu_iss.rvs1_elem := acc_data
          vxu_iss.rvs1_data := acc_data
          vxu_iss.rvs1_eew  := vxs_iss.vd_eew
          vxu_iss.rvs2_data := rvs2_elem
        } .otherwise {
          vxu_iss.rvs1_data := acc_data
          vxu_iss.rvs1_eew := vxs_iss.vd_eew
        }
      }
    }
  }

  io.executionPerf.dependencyWait := perfCount(flat_vxs.map(_.io.waitReason.dependency).reduce(_ || _))
  io.executionPerf.operandWait := perfCount(flat_vxs.map(_.io.waitReason.operands).reduce(_ || _))
  io.executionPerf.feedbackWait := perfCount(flat_vxs.map(_.io.waitReason.feedback).reduce(_ || _))
  io.executionPerf.permutationWait := perfCount(flat_vxs.map(_.io.waitReason.permutation).reduce(_ || _))
  io.executionPerf.writebackWait := perfCount(flat_vxs.map(_.io.waitReason.writeback).reduce(_ || _))
  io.executionPerf.executionWait := perfCount(flat_vxs.map(_.io.waitReason.execution).reduce(_ || _))
  io.executionPerf.earlyOperandReads := perfSum(flat_vxs.map(_.io.earlyOperandReads).reduce(_ +& _))
  io.executionPerf.reductionPrefetches := perfSum(flat_vxs.map(_.io.reductionPrefetch.asUInt).reduce(_ +& _))

  // Contexts share one arithmetic unit and one physical result channel.
  // Reserve output time across all contexts, including different latencies.
  for (((seqs, xu), factory) <- contextsByFu.zip(flat_vxus).zip(xissParams.flatMap(_.seqs))) {
    seqs.foreach(_.io.lookahead := 0.U.asTypeOf(seqs.head.io.lookahead))
    val lookaheadEligible = WireInit(false.B)
    val slideLookaheadEligible = WireInit(false.B)
    if (vParams.crossInstructionPrefetch || factory.name == "fp_exp") {
      val queueIndex = xissParams.indexWhere(_.seqs.exists(_.name == factory.name))
      val next = vxissqs(queueIndex).io.deq
      val prefetch = Module(new InstructionPrefetch(factory.insns, forwardWriters.size,
        fallback = factory.name == "fp_exp"))
      prefetch.io.next.valid := next.valid
      prefetch.io.next.bits := next.bits.viewAsSupertype(new BackendIssueInst)
      prefetch.io.accepts := seqs.head.accepts(next.bits)
      prefetch.io.atTail := seqs.head.io.atTail
      prefetch.io.vatHead := io.vat_head
      prefetch.io.vatTail := io.vat_tail
      prefetch.io.writers := VecInit(forwardWriters.toSeq)
      seqs.head.io.lookahead := prefetch.io.hints
      lookaheadEligible := prefetch.io.hints.map(_.valid).reduce(_ || _)
      slideLookaheadEligible := prefetch.io.slide
    }
    val criticalPriority = factory.name == "fp_fma" || factory.name == "fp_cmp"
    val arb = Module(new SharedExecutionArbiter(seqs.size, maxPipeDepth, xu.nFUs, criticalPriority))
    for ((seq, c) <- seqs.zipWithIndex) { arb.io.in(c) <> executeCandidates(flat_vxs.indexOf(seq)) }
    xu.io.iss <> arb.io.out
    val perfIndex = EvuFuPerf.names.indexOf(factory.name)
    if (perfIndex >= 0 && (vParams.diagnosticCounters || !Set("int", "fp_div").contains(factory.name))) {
      val counters = io.executionPerf.byFu(perfIndex)
      val queueIndex = xissParams.indexWhere(_.seqs.exists(_.name == factory.name))
      val queue = vxissqs(queueIndex)
      counters.issue := perfCount(xu.io.iss.fire)
      counters.busy := diagnosticCount(s"diag_${factory.name}_busy",
        seqs.map(_.io.busy).reduce(_ || _) || xu.io.busy)
      counters.dependencyWait := perfCount(seqs.map(_.io.waitReason.dependency).reduce(_ || _))
      counters.operandWait := perfCount(seqs.map(_.io.waitReason.operands).reduce(_ || _))
      counters.feedbackWait := perfCount(seqs.map(_.io.waitReason.feedback).reduce(_ || _))
      counters.permutationWait := diagnosticCount(s"diag_${factory.name}_permutationWait",
        seqs.map(_.io.waitReason.permutation).reduce(_ || _))
      counters.writebackWait := perfCount(seqs.map(_.io.waitReason.writeback).reduce(_ || _))
      counters.executionWait := perfCount(seqs.map(_.io.waitReason.execution).reduce(_ || _))
      counters.queueWait := perfCount(queue.io.deq.valid && !queue.io.deq.ready)
      counters.dispatchBlocked := perfCount(vdq.io.deq.valid && issq_stall(3 + queueIndex))
      val pendingWork = queue.io.deq.valid || seqs.map(_.io.busy).reduce(_ || _)
      if (factory.name == "int" && vParams.diagnosticCounters) {
        val next = queue.io.deq.bits
        val idle = queue.io.deq.valid && !queue.io.deq.ready && !xu.io.iss.fire
        val candidate = idle && IntegerContextRules.accepts(next, dLen, vParams.alignedSlide, vParams.singleGroupSlide)
        def archMask(groups: UInt): UInt = VecInit((0 until 32).map(r =>
          groups((r + 1) * egsPerVReg - 1, r * egsPerVReg).orR)).asUInt
        val reads = queue.io.hazards.map(h => Mux(h.valid && h.bits.vat === next.vat,
          h.bits.rintent, 0.U(32.W))).foldLeft(0.U(32.W))(_ | _)
        val writes = queue.io.hazards.map(h => Mux(h.valid && h.bits.vat === next.vat,
          h.bits.wintent, 0.U(32.W))).foldLeft(0.U(32.W))(_ | _)
        val olderWrites = forwardWriters.map(h => Mux(h.valid && vatOlder(h.bits.vat, next.vat),
          archMask(h.bits.writes), 0.U(32.W))).foldLeft(0.U(32.W))(_ | _)
        val queueReads = allIssQs.flatMap(_.io.hazards).map(h =>
          Mux(h.valid && vatOlder(h.bits.vat, next.vat), h.bits.rintent, 0.U(32.W)))
        val contextReads = allSeqs.map(_.io.seq_hazard).map(h =>
          Mux(h.valid && vatOlder(h.bits.vat, next.vat), archMask(h.bits.rintent), 0.U(32.W)))
        val olderReads = (queueReads ++ contextReads).foldLeft(0.U(32.W))(_ | _)
        val raw = IntegerContextRules.raw(reads, olderWrites)
        val destination = IntegerContextRules.destination(writes, olderReads, olderWrites)
        io.executionPerf.intCandidateIdle := diagnosticCount("diag_intCandidateIdle", candidate)
        io.executionPerf.intCandidateRaw := diagnosticCount("diag_intCandidateRaw", candidate && raw)
        io.executionPerf.intCandidateDestination := diagnosticCount("diag_intCandidateDestination", candidate && destination)
        io.executionPerf.intCandidateIndependent := diagnosticCount("diag_intCandidateIndependent", candidate && !raw && !destination)
        val secondIssue = seqs.drop(1).map(_.io.iss.fire).foldLeft(false.B)(_ || _)
        io.executionPerf.intSecondIssues := diagnosticCount("diag_intSecondIssues", secondIssue)
        io.executionPerf.intSecondOverlapIssues := diagnosticCount("diag_intSecondOverlapIssues", secondIssue &&
          (seqs.head.io.waitReason.dependency || seqs.head.io.waitReason.operands))
      }
      counters.noWork := diagnosticCount(s"${factory.name}_noWork", !pendingWork)
      val loadWrites = vls.io.seq_hazard.bits.wintent & Fill(egsTotal, vls.io.seq_hazard.valid) |
        vlissq.io.hazards.map(h => Mux(h.valid, FillInterleaved(egsPerVReg, h.bits.wintent), 0.U)).reduce(_ | _)
      val operands = seqs.filter(_.io.forward.nonEmpty).flatMap { seq =>
        val base = forwardSeqs.indexOf(seq) * 3
        Seq(seq.io.rvs1, seq.io.rvs2, seq.io.rvd).zipWithIndex.map { case (read, port) =>
          (seq.io.forward.get(port).eligible, read, base + port)
        }
      }
      counters.loadWait := diagnosticCount(s"${factory.name}_loadWait",
        seqs.map(s => s.io.busy && (s.io.rawDependencies & loadWrites).orR).reduce(_ || _))
      counters.forwardWait := diagnosticCount(s"${factory.name}_forwardWait", operands.map {
        case (eligible, read, index) => eligible && !forwarding.io.out(index).valid &&
          forwardSeqs(index / 3).io.rawDependencies(read.req.bits.eg) && !loadWrites(read.req.bits.eg)
      }.foldLeft(false.B)(_ || _))
      counters.idleWithWork := diagnosticCount(s"${factory.name}_idleWithWork", pendingWork && !xu.io.iss.fire)
      counters.readyArbitrationWait := diagnosticCount(s"${factory.name}_readyArbitrationWait", arb.io.contended)
      counters.queueIdle := diagnosticCount(s"${factory.name}_queueIdle",
        queue.io.deq.valid && !queue.io.deq.ready && !xu.io.iss.fire)
      counters.resultSlotWait := diagnosticCount(s"${factory.name}_resultSlotWait",
        seqs.zipWithIndex.map { case (seq, c) =>
          seq.io.pipe_write_req.request && !arb.io.available(c)
        }.reduce(_ || _))
      counters.priorityIssues := diagnosticCount(s"${factory.name}_priorityIssues", arb.io.priorityIssue)
      if (vParams.diagnosticCounters) {
        counters.lookaheadEligible := diagnosticCount(s"diag_${factory.name}_lookaheadEligible", lookaheadEligible)
        counters.lookaheadIdle := diagnosticCount(s"diag_${factory.name}_lookaheadIdle", lookaheadEligible && !xu.io.iss.fire)
        counters.slideLookaheadEligible := diagnosticCount(s"diag_${factory.name}_slideLookaheadEligible",
          slideLookaheadEligible)
        val ordinaryBlocked = queue.io.deq.valid && !queue.io.deq.bits.reduction && !queue.io.deq.ready
        counters.ordinaryFull := diagnosticCount(s"diag_${factory.name}_ordinaryFull", ordinaryBlocked)
        counters.ordinaryFullIdle := diagnosticCount(s"diag_${factory.name}_ordinaryFullIdle",
          ordinaryBlocked && !xu.io.iss.fire)
        counters.reductionFull := diagnosticCount(s"diag_${factory.name}_reductionFull",
          queue.io.deq.valid && queue.io.deq.bits.reduction && !queue.io.deq.ready)
        counters.operandsReadyIdle := diagnosticCount(s"diag_${factory.name}_operandsReadyIdle",
          seqs.map(_.io.operandsReady).reduce(_ || _) && !xu.io.iss.fire)
        counters.eligibleIdle := diagnosticCount(s"diag_${factory.name}_eligibleIdle",
          arb.io.out.valid && !xu.io.iss.fire)
        counters.readyLosers := diagnosticCount(s"diag_${factory.name}_readyLosers", PopCount(VecInit(
          arb.io.in.zip(arb.io.available).map { case (in, available) =>
            in.valid && available && !in.fire && xu.io.iss.fire
          })))
        counters.bankReservation := diagnosticCount(s"diag_${factory.name}_bankReservation",
          seqs.map(s => s.io.pipe_write_req.request &&
            !vrf.io.vxs(flat_vxs.indexOf(s)).pipe_write_req.available).reduce(_ || _))
        counters.contextCycles := diagnosticCount(s"diag_${factory.name}_contextCycles",
          PopCount(VecInit(seqs.map(_.io.busy))))
        val target = seqs.map(_.accepts(vdq.io.deq.bits)).reduce(_ || _)
        counters.dispatchReduction := diagnosticCount(s"diag_${factory.name}_dispatchReduction",
          vdq.io.deq.valid && target && reductionBlocked)
        for ((stage, j) <- EvuReductionStage.names.zipWithIndex) {
          def matches(op: ExecuteMicroOp): Bool = op.acc && (j match {
            case 0 => !op.acc_merge && !op.acc_fold && !op.acc_direct
            case 1 => op.acc_merge
            case 2 => op.acc_fold || op.acc_direct
            case _ => false.B
          })
          val issued = xu.io.iss.fire && matches(xu.io.iss.bits)
          val stat = counters.stages(j)
          stat.issues := diagnosticCount(s"diag_${factory.name}_${stage}_issues", issued)
          stat.elements16 := diagnosticCount(s"diag_${factory.name}_${stage}_elements16",
            Mux(issued && xu.io.iss.bits.vd_eew === 1.U, EvuOpClass.elements(xu.io.iss.bits, 1), 0.U))
          stat.elements32 := diagnosticCount(s"diag_${factory.name}_${stage}_elements32",
            Mux(issued && xu.io.iss.bits.vd_eew === 2.U, EvuOpClass.elements(xu.io.iss.bits, 2), 0.U))
          stat.feedback := diagnosticCount(s"diag_${factory.name}_${stage}_feedback", PopCount(VecInit(
            seqs.map(s => matches(s.io.iss.bits) && s.io.busy && s.io.waitReason.feedback))))
          stat.operands := diagnosticCount(s"diag_${factory.name}_${stage}_operands", PopCount(VecInit(
            seqs.map(s => matches(s.io.iss.bits) && s.io.busy && s.io.waitReason.operands))))
          stat.resultReadyWait := diagnosticCount(s"diag_${factory.name}_${stage}_resultReadyWait", PopCount(VecInit(
            seqs.map(s => matches(s.io.iss.bits) && s.io.busy && s.io.acc_valid && !s.io.iss.fire))))
          stat.returnIssues := diagnosticCount(s"diag_${factory.name}_${stage}_returnIssues",
            PopCount(VecInit(seqs.map(s => matches(s.io.iss.bits) && s.io.iss.fire && s.io.acc_return))))
        }
      }
    }
    if (factory.name == "fp_fma") {
      val previousVat = RegEnable(xu.io.iss.bits.vat, xu.io.iss.fire)
      val previousValid = RegNext(xu.io.iss.fire, false.B)
      val previousReduction = RegEnable(xu.io.iss.bits.acc, false.B, xu.io.iss.fire)
      io.executionPerf.fmaIssue := perfCount(xu.io.iss.fire)
      io.executionPerf.fmaNoWork := perfCount(!seqs.map(_.io.busy).reduce(_ || _))
      io.executionPerf.fmaOperandWait := perfCount(seqs.map(_.io.busy).reduce(_ || _) && !arb.io.out.valid)
      io.executionPerf.fmaFeedbackWait := perfCount(seqs.filter(_.reductionOnly)
        .map(s => s.io.busy && !s.io.acc_valid).foldLeft(false.B)(_ || _))
      io.executionPerf.fmaInterleave := perfCount(xu.io.iss.fire && previousValid &&
        previousReduction && xu.io.iss.bits.acc && xu.io.iss.bits.vat =/= previousVat &&
        (seqs.count(_.reductionOnly) > 1).B)
    }
    if (factory.name == "fp_exp") { io.executionPerf.expIssue := perfCount(xu.io.iss.fire) }
    if (factory.name == "fp_div") { io.executionPerf.divIssue := perfCount(xu.io.iss.fire) }
    for ((seq, c) <- seqs.zipWithIndex) {
      val idx = flat_vxs.indexOf(seq)
      vrf.io.vxs(idx).pipe_write_req.request := seq.io.pipe_write_req.request
      vrf.io.vxs(idx).pipe_write_req.bank_sel := seq.io.pipe_write_req.bank_sel
      vrf.io.vxs(idx).pipe_write_req.pipe_depth := seq.io.pipe_write_req.pipe_depth
      vrf.io.vxs(idx).pipe_write_req.oldest := seq.io.pipe_write_req.oldest
      vrf.io.vxs(idx).pipe_write_req.fire := seq.io.pipe_write_req.fire
      seq.io.pipe_write_req.available := vrf.io.vxs(idx).pipe_write_req.available &&
        arb.io.available(c)
    }
  }

  val frontend_rindex = Wire(new VectorReadIO)
  val frontend_rmask  = Wire(new VectorReadIO)
  vrf.io.frontend.rindex <> frontend_rindex
  vrf.io.frontend.rmask <> frontend_rmask

  // ============================
  // Connect writes to VRF
  for (i <- 0 until flat_vxs.size) {
    vrf.io.pipe_writes(i) := 0.U.asTypeOf(vrf.io.pipe_writes(i))
    vrf.io.iter_writes(i).valid := false.B
    vrf.io.iter_writes(i).bits := 0.U.asTypeOf(vrf.io.iter_writes(i).bits)
    vrf.io.iterOwners(i) := 0.U
  }
  for ((xu, i) <- flat_vxus.zipWithIndex) {
    val port = representativeByFu(i)
    vrf.io.pipe_writes(port) <> xu.io.pipe_write
    vrf.io.iter_writes(port) <> xu.io.iter_write
    vrf.io.iterOwners(port) := xu.io.iterWriteVat
  }

  val load_write = Wire(Decoupled(new VectorWrite(dLen)))
  vrf.io.load_write <> load_write
  vrf.io.loadOwner := vls.io.iss.bits.vat


  io.vmu.lresp.ready := vls.io.iss.valid && load_write.ready
  vls.io.iss.ready := io.vmu.lresp.valid && load_write.ready
  load_write.valid := vls.io.iss.valid && io.vmu.lresp.valid
  load_write.bits.eg   := vls.io.iss.bits.wvd_eg
  load_write.bits.data := Fill(dLen / mLen, io.vmu.lresp.bits.data)
  val load_wmask = Mux(vls.io.iss.bits.use_rmask,
    get_vm_mask(vrf.io.vls.rvm.resp, vls.io.iss.bits.eidx, vls.io.iss.bits.elem_size, dLen),
    ~(0.U(dLenB.W)))
  load_write.bits.mask := FillInterleaved(8, vls.io.iss.bits.eidx_wmask & load_wmask)
  when (io.vmu.lresp.fire) {
    assert(io.vmu.lresp.bits.debug_id === vls.io.iss.bits.debug_id)
  }


  // ========================================
  // Connect frontend index/mask access ports

  val index_access_eg = getEgId(io.index_access.vrs, io.index_access.eidx, io.index_access.eew, false.B)
  val index_access_eg_oh = UIntToOH(index_access_eg)
  val index_access_hazard = ((allSeqs.map(_.io.seq_hazard).map { h =>
    h.valid && ((h.bits.wintent & index_access_eg_oh) =/= 0.U)
  } ++ allIssQs.map(_.io.hazards).flatten.map { h =>
    h.valid && h.bits.wintent(io.index_access.vrs)
  } ++ vxus.flatten.map(_.io.pipe_hazards).flatten.map { h =>
    h.valid && h.bits.eg === index_access_eg
  } ++ vxus.flatten.map(_.io.iter_hazards).flatten.map { h =>
    h.valid && h.bits.eg === index_access_eg
  } ++ pendingWrites.map { h =>
    h.valid && h.bits.eg === index_access_eg
  }).orR ||
    vdq.io.peek.map(i => i.valid && !(i.bits.vmu && i.bits.store)).orR
  )
  // TODO: this conservatively assumes a index data hazard against anything in the vdq

  frontend_rindex.req.valid := io.index_access.valid && !index_access_hazard
  io.index_access.ready := frontend_rindex.req.ready && !index_access_hazard
  frontend_rindex.req.bits.eg  := index_access_eg
  frontend_rindex.req.bits.oldest  := false.B
  frontend_rindex.req.bits.lastOperand := false.B
  io.index_access.idx   := frontend_rindex.resp >> ((io.index_access.eidx << io.index_access.eew)(dLenOffBits-1,0) << 3) & eewBitMask(io.index_access.eew)

  val vm_busy = Wire(Bool())
  frontend_rmask.req.valid    := io.mask_access.valid && !vm_busy
  frontend_rmask.req.bits.eg  := getEgId(0.U, io.mask_access.eidx, 0.U, true.B)
  frontend_rmask.req.bits.oldest := false.B
  frontend_rmask.req.bits.lastOperand := false.B
  io.mask_access.ready  := frontend_rmask.req.ready && !vm_busy
  io.mask_access.mask   := frontend_rmask.resp >> io.mask_access.eidx(log2Ceil(dLen)-1,0)

  // =====================================
  // Connect VMU index/mask access ports

  val vmu_index_q = Module(new Compactor(dLenB, mLenB, UInt(8.W), false))
  val vmu_mask_q = Module(new Compactor(dLenB, mLenB, Bool(), false))
  val vgu = Module(new GatherUnit)

  vmu_index_q.io.push_data      := vps.io.rvs2_data.asTypeOf(Vec(dLenB, UInt(8.W)))
  vmu_index_q.io.push.bits.head := vps.io.iss.bits.eidx << vps.io.iss.bits.rvs2_eew
  vmu_index_q.io.push.bits.tail := Mux(vps.io.iss.bits.tail,
    vps.io.iss.bits.vl << vps.io.iss.bits.rvs2_eew,
    0.U)

  vmu_mask_q.io.push_data       := Mux(vps.io.iss.bits.renvm,
    (vps.io.rvm_data >> vps.io.iss.bits.eidx(log2Ceil(dLen)-1,0))(dLenB-1,0),
    ~(0.U(dLenB.W))
  ).asBools
  vmu_mask_q.io.push.bits.head  := 0.U
  vmu_mask_q.io.push.bits.tail  := (Mux(vmu_index_q.io.push.bits.tail === 0.U, dLenB.U, vmu_index_q.io.push.bits.tail) - vmu_index_q.io.push.bits.head) >> vps.io.iss.bits.rvs2_eew

  vps.io.iss.ready := Mux(vps.io.iss.bits.vmu,
    vmu_index_q.io.push.ready && vmu_mask_q.io.push.ready,
    vgu.io.vps.ready)

  vmu_index_q.io.push.valid := vps.io.iss.valid && vps.io.iss.bits.vmu && vps.io.iss.bits.renv2 && vps.io.iss.ready
  vmu_mask_q.io.push.valid  := vps.io.iss.valid && vps.io.iss.bits.vmu && vps.io.iss.bits.renvm && vps.io.iss.ready

  io.vmu.sdata.valid   := vss.io.iss.valid
  vss.io.iss.ready     := io.vmu.sdata.ready
  val iss_stdata = if (mLen < dLen) {
    val select = (vss.io.iss.bits.eidx << vss.io.iss.bits.elem_size)(dLenOffBits-1,mLenOffBits)
    vss.io.rvd_data.asTypeOf(Vec(dLen/mLen, UInt(mLen.W)))(select)
  } else {
    vss.io.rvd_data
  }
  io.vmu.sdata.bits.stdata    := iss_stdata
  io.vmu.sdata.bits.stmask    := vss.io.iss.bits.eidx_mask & Mux(vss.io.iss.bits.use_stmask,
    get_vm_mask(vss.io.rvm_data, vss.io.iss.bits.eidx, vss.io.iss.bits.elem_size, mLen),
    ~(0.U(mLenB.W))
  )
  io.vmu.sdata.bits.debug_id := vss.io.iss.bits.debug_id


  io.vmu.mask_pop   <> vmu_mask_q.io.pop
  io.vmu.mask_data  := vmu_mask_q.io.pop_data
  io.vmu.index_pop  <> vmu_index_q.io.pop
  io.vmu.index_data := vmu_index_q.io.pop_data

  // ==================================
  // Connect Permute-to-Execute path

  vgu.io.vps.valid := vps.io.iss.valid && !vps.io.iss.bits.vmu
  vgu.io.vps.bits.viewAsSupertype(new SpecialMicroOp) := vps.io.iss.bits
  vgu.io.vps.bits.rvs2_data := vps.io.rvs2_data

  // Only the first VSU can handle permutations TODO clean this up
  flat_vxs.foreach(_.io.vgu.slide_req.ready := false.B)
  flat_vxs.foreach(_.io.vgu.slide_data := DontCare)
  flat_vxs.foreach(_.io.vgu.gather_eidx.valid := false.B)
  flat_vxs.foreach(_.io.vgu.gather_eidx.bits := DontCare)

  val perm_vxs = flat_vxs.filter(_.ownsPermutationStream)
  require(perm_vxs.size == 1)
  vgu.io.vxs <> perm_vxs.head.io.vgu

  // =================================
  // Connect ReductionSeq to sequencer

  for (seq <- reductionSeqs) {
    seq.io.acc_data.ready := false.B
    seq.io.acc_slot := 0.U
    seq.io.acc_other_slot := 0.U
    seq.io.acc_merge := false.B
    seq.io.acc_done := false.B
  }
  for (vxs <- flat_vxs) {
    vxs.io.acc_valid := false.B
    vxs.io.acc_return := false.B
    if (vxs.usesAcc) {
      for (seq <- reductionSeqs) {
        when (vxs.io.vat === seq.io.vat && vxs.io.busy && seq.io.busy && !vxs.io.iss.bits.acc_direct) {
          seq.io.acc_data.ready := vxs.io.acc_ready && vxs.io.iss.ready
          seq.io.acc_slot := vxs.io.acc_slot
          seq.io.acc_other_slot := vxs.io.acc_other_slot
          seq.io.acc_merge := vxs.io.acc_merge
          vxs.io.acc_valid := seq.io.acc_data.valid
          vxs.io.acc_return := seq.io.acc_return
          when (vxs.io.iss.fire && vxs.io.iss.bits.tail && vxs.io.iss.bits.acc) {
            seq.io.acc_done := true.B
          }
        }
      }
    }
  }

  // ==============================
  // Connect ReductionSeq to writebacks

  val accRouter = Module(new ReductionReturnRouter(vParams.vatSz, log2Ceil(egsTotal), dLen,
    reductionSeqs.size, flat_vxus.size))
  for ((xu, i) <- flat_vxus.zipWithIndex) {
    accRouter.io.returns(i).valid := xu.io.acc_write.valid
    accRouter.io.returns(i).bits.vat := xu.io.accWriteVat
    accRouter.io.returns(i).bits.slot := xu.io.acc_write_slot
    accRouter.io.returns(i).bits.eg := xu.io.acc_write.bits.eg
    accRouter.io.returns(i).bits.data := xu.io.acc_write.bits.data
    accRouter.io.returns(i).bits.mask := xu.io.acc_write.bits.mask
  }
  for ((seq, i) <- reductionSeqs.zipWithIndex) {
    accRouter.io.owners(i).valid := seq.io.busy
    accRouter.io.owners(i).bits := seq.io.vat
    seq.io.acc_fu_resp.valid := accRouter.io.routed(i).valid
    seq.io.acc_fu_resp.bits.eg := accRouter.io.routed(i).bits.eg
    seq.io.acc_fu_resp.bits.data := accRouter.io.routed(i).bits.data
    seq.io.acc_fu_resp.bits.mask := accRouter.io.routed(i).bits.mask
    seq.io.acc_fu_resp_slot := accRouter.io.routed(i).bits.slot
  }

  // Clear the age tags
  var r_idx = 0
  def clearVat(fire: Bool, tag: UInt) = {
    assert(r_idx < nRelease)
    io.vat_release(r_idx).valid := fire
    io.vat_release(r_idx).bits := tag
    r_idx += 1
  }

  val writeCompletion = Module(new WriteCompletionTracker(vParams.vatSz, flat_vxus.size + 1,
    diagnostics = vParams.diagnosticCounters))
  writeCompletion.io.tail := io.vat_tail
  writeCompletion.io.reportHead.foreach { head =>
    head := io.reportHead.getOrElse(0.U.asTypeOf(head))
  }
  io.executionPerf.completionHeadWriteWait := diagnosticCount("diag_completionHeadWriteWait", writeCompletion.io.headWriteWait)
  io.executionPerf.completionHeadTrackerWait := diagnosticCount("diag_completionHeadTrackerWait", writeCompletion.io.headTrackerWait)
  writeCompletion.io.tails(0).valid := vls.io.iss.fire && vls.io.iss.bits.tail
  writeCompletion.io.tails(0).bits := vls.io.iss.bits.vat
  for ((xu, i) <- flat_vxus.zipWithIndex) { writeCompletion.io.tails(i + 1) := xu.io.completed }
  writeCompletion.io.pendingWrites := pendingWrites.map { h =>
    Mux(h.valid, UIntToOH(h.bits.vat, 1 << vParams.vatSz), 0.U)
  }.reduce(_ | _)
  clearVat(writeCompletion.io.release.valid, writeCompletion.io.release.bits)
  clearVat(vss.io.iss.fire && vss.io.iss.bits.tail, vss.io.iss.bits.vat)
  flat_vxus.foreach(_ => clearVat(false.B, 0.U))

  // Op classes decode FP funct6 values; integer/divider FU counters are separate.
  val opClassPaths = Set("fp_fma", "fp_cmp", "fp_exp", "fp_xx8", "fp_mxfp", "fp_bf16")
  if (vParams.diagnosticCounters && xissParams.flatMap(_.seqs).exists(s => opClassPaths.contains(s.name))) {
    val fpSeqs = contextsByFu.zip(xissParams.flatMap(_.seqs)).collect {
      case (seqs, factory) if opClassPaths.contains(factory.name) => seqs
    }.flatten
    val fpUnits = flat_vxus.zip(xissParams.flatMap(_.seqs)).collect {
      case (unit, factory) if opClassPaths.contains(factory.name) => unit
    }
    val classes = fpUnits.map(u => EvuOpClass(u.io.iss.bits))
    val seqClasses = fpSeqs.map(s => EvuOpClass(s.io.iss.bits))
    val laneCounts = Seq(1, 2).map(sew => fpUnits.map(u => EvuOpClass.elements(u.io.iss.bits, sew)))
    val trace = PlusArg("evu_cmd_trace", width = 1)
    val ticks = RegInit(0.U(64.W)).suggestName("diag_ticks")
    ticks := ticks + 1.U
    val n = 1 << vParams.vatSz
    val entered = Reg(Vec(n, UInt(64.W))).suggestName("diag_entered")
    val contextAt = Reg(Vec(n, UInt(64.W))).suggestName("diag_contextAt")
    val started = Reg(Vec(n, UInt(64.W))).suggestName("diag_started")
    val hasStarted = RegInit(VecInit.fill(n)(false.B)).suggestName("diag_hasStarted")
    val kinds = Reg(Vec(n, UInt(EvuOpClass.width.W))).suggestName("diag_kinds")
    when(io.dis.fire) {
      entered(io.dis.bits.vat) := ticks
      hasStarted(io.dis.bits.vat) := false.B
    }
    for (unit <- fpUnits) {
      when(unit.io.iss.fire && unit.io.iss.bits.head) {
        val vat = unit.io.iss.bits.vat
        started(vat) := ticks
        hasStarted(vat) := true.B
        kinds(vat) := EvuOpClass(unit.io.iss.bits)
      }
    }
    val done = writeCompletion.io.release
    val elapsed = ticks - started(done.bits)
    for ((name, k) <- EvuOpClass.names.zipWithIndex) {
      val stat = io.executionPerf.byOp(k)
      val fires = fpUnits.zip(classes).map { case (u, cls) => u.io.iss.fire && cls === k.U }
      stat.instructions := diagnosticCount(s"diag_${name}_instructions", PopCount(VecInit(
        fpUnits.zip(fires).map { case (u, fire) => fire && u.io.iss.bits.head })))
      stat.issues := diagnosticCount(s"diag_${name}_issues", PopCount(VecInit(fires)))
      def elements(sew: Int): UInt = fpUnits.zip(fires).zip(laneCounts(sew - 1)).map { case ((u, fire), lanes) =>
        val lowp = xiangshan.evu.EvuLowPrecision.backend(u.io.iss.bits.funct3, u.io.iss.bits.funct6, u.io.iss.bits.rs1)
        Mux(fire && Mux(lowp, (sew == 2).B, u.io.iss.bits.vd_eew === sew.U), lanes, 0.U)
      }.reduce(_ +& _)
      stat.elements16 := diagnosticCount(s"diag_${name}_elements16", elements(1))
      stat.elements32 := diagnosticCount(s"diag_${name}_elements32", elements(2))
      def waits(reason: ExecuteWaitSignals => Bool): UInt = PopCount(VecInit(
        fpSeqs.zip(seqClasses).map { case (s, cls) => s.io.busy && cls === k.U && reason(s.io.waitReason) }))
      stat.dependency := diagnosticCount(s"diag_${name}_dependency", waits(_.dependency))
      stat.operands := diagnosticCount(s"diag_${name}_operands", waits(_.operands))
      stat.feedback := diagnosticCount(s"diag_${name}_feedback", waits(_.feedback))
      val completed = done.valid && hasStarted(done.bits) && kinds(done.bits) === k.U
      stat.completed := diagnosticCount(s"diag_${name}_completed", completed)
      stat.latency := diagnosticCount(s"diag_${name}_latency", Mux(completed, elapsed, 0.U))
    }
    when(done.valid && hasStarted(done.bits)) {
      when(trace.asBool && !reset.asBool) {
        printf(p"[EVU-CMD-EXEC] event=complete vat=${done.bits} op=${kinds(done.bits)} queue=${contextAt(done.bits) - entered(done.bits)} first_wait=${started(done.bits) - contextAt(done.bits)} execute=${elapsed}\n")
      }
      hasStarted(done.bits) := false.B
    }
    // Per-context counters avoid a PC-indexed hardware table. The wrapper emits
    // the VAT/job/epoch/PC mapping; the host joins records within that lifetime.
    for ((s, index) <- fpSeqs.zipWithIndex) {
      val accepted = Reg(UInt(64.W)).suggestName(s"diag_contextStart_$index")
      val dep = RegInit(0.U(32.W)).suggestName(s"diag_contextDep_$index")
      val operands = RegInit(0.U(32.W)).suggestName(s"diag_contextOperands_$index")
      val feedback = RegInit(0.U(32.W)).suggestName(s"diag_contextFeedback_$index")
      val wb = RegInit(0.U(32.W)).suggestName(s"diag_contextWb_$index")
      val execution = RegInit(0.U(32.W)).suggestName(s"diag_contextExecution_$index")
      when(s.io.busy) {
        dep := dep + s.io.waitReason.dependency
        operands := operands + s.io.waitReason.operands
        feedback := feedback + s.io.waitReason.feedback
        wb := wb + s.io.waitReason.writeback
        execution := execution + s.io.waitReason.execution
      }
      when(s.io.iss.fire && s.io.iss.bits.tail && trace.asBool && !reset.asBool) {
        printf(p"[EVU-CMD-EXEC] event=tail vat=${s.io.vat} op=${EvuOpClass(s.io.iss.bits)} context=${index.U} residence=${ticks - accepted} dep=${dep} operand=${operands} feedback=${feedback} wb=${wb} execution=${execution}\n")
      }
      when(s.io.dis.fire) {
        accepted := ticks
        contextAt(s.io.dis.bits.vat) := ticks
        dep := 0.U
        operands := 0.U
        feedback := 0.U
        wb := 0.U
        execution := 0.U
      }
    }
  }

  // Accumulate each instruction's active-element flags through its final writeback.
  val fflagsByVat = RegInit(VecInit.fill(1 << vParams.vatSz)(0.U(5.W)))
  val currentFflags = Wire(chiselTypeOf(fflagsByVat))
  val taggedFflags = flat_vxus.flatMap(_.io.tagged_fflags)
  for (vat <- 0 until (1 << vParams.vatSz)) {
    val updates = taggedFflags.map(f => Mux(f.valid && f.bits.vat === vat.U, f.bits.flags, 0.U))
    currentFflags(vat) := fflagsByVat(vat) | updates.reduce(_ | _)
    fflagsByVat(vat) := Mux(io.dis.fire && io.dis.bits.vat === vat.U, 0.U, currentFflags(vat))
  }
  for (i <- 0 until nRelease) {
    io.completion_fflags(i) := currentFflags(io.vat_release(i).bits)
  }

  val vxsatByVat = RegInit(VecInit.fill(1 << vParams.vatSz)(false.B))
  val currentVxsat = Wire(chiselTypeOf(vxsatByVat))
  val taggedVxsat = flat_vxus.flatMap(_.io.tagged_vxsat)
  for (vat <- 0 until (1 << vParams.vatSz)) {
    currentVxsat(vat) := vxsatByVat(vat) || taggedVxsat.map(f => f.valid && f.bits === vat.U).reduce(_ || _)
    vxsatByVat(vat) := !(io.dis.fire && io.dis.bits.vat === vat.U) && currentVxsat(vat)
  }
  for (i <- 0 until nRelease) {
    io.completion_vxsat(i) := currentVxsat(io.vat_release(i).bits)
  }

  // Signalling to frontend
  val seq_inflight_wv0 = (allSeqs.map(_.io.seq_hazard).map { h =>
    h.valid && ((h.bits.wintent & ~(0.U(egsPerVReg.W))) =/= 0.U)
  } ++ allIssQs.map(_.io.hazards).flatten.map { h =>
    h.valid && h.bits.wintent(0)
  } ++ vxus.flatten.map(_.io.pipe_hazards).flatten.map { h =>
    h.valid && (h.bits.eg < egsPerVReg.U)
  } ++ vxus.flatten.map(_.io.iter_hazards).flatten.map { h =>
    h.valid && (h.bits.eg < egsPerVReg.U)
  } ++ pendingWrites.map { h =>
    h.valid && h.bits.eg < egsPerVReg.U
  }).orR
  val vdq_inflight_wv0 = vdq.io.peek.map { h =>
    h.valid && h.bits.may_write_v0
  }.orR

  vm_busy := seq_inflight_wv0 || vdq_inflight_wv0
  io.busy := vdq.io.deq.valid || allSeqs.map(_.io.busy).orR || vxus.flatten.map(_.io.busy).asUInt.orR ||
    pendingWrites.map(_.valid).orR || writeCompletion.io.busy
  io.set_vxsat := vxus.flatten.map(_.io.set_vxsat).asUInt.orR
  io.set_fflags.valid := vxus.flatten.map(_.io.set_fflags.valid).asUInt.orR
  io.set_fflags.bits  := vxus.flatten.map( xu => Mux(xu.io.set_fflags.valid, xu.io.set_fflags.bits, 0.U)).reduce(_|_)

  // Only one of these should actually be connected
  val scalar_write_arb = Module(new Arbiter(new ScalarWrite, flat_vxus.size))
  vxus.flatten.map(_.io.scalar_write).zip(scalar_write_arb.io.in).foreach { case (i,o) => o <> i }
  io.scalar_resp <> scalar_write_arb.io.out
}
