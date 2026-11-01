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

    val vat_release = Output(Vec(nRelease, Valid(UInt(vParams.vatSz.W))))
    val completion_vxsat = Output(Vec(nRelease, Bool()))
    val completion_fflags = Output(Vec(nRelease, UInt(5.W)))

    // Special AI operations are sequenced by the EVU wrapper but access the
    // same Saturn RegisterFile through these requester-side ports.
    // RegisterAccess owns the VRF requester-side ports. From this modules
    // perspective they are inputs, so flip both request bundles here; this
    // avoids output-to-output bulk connections when the special sequencer is
    // wired through EvuVectorSubsystem.
    val special = new Bundle {
      val read = Vec(3, Flipped(new VectorReadIO))
    }
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
  val contextCounts = xissParams.map(_.seqs.map { s =>
    if (interleave && (s.name == "fp_fma" || s.name == "fp_cmp")) 1 + vParams.reductionContexts else 1
  })

  val vlissq = Module(new IssueQueue(vParams.vlissqEntries, 1))
  val vsissq = Module(new IssueQueue(vParams.vsissqEntries, 1))
  val vpissq = Module(new IssueQueue(vParams.vpissqEntries, vParams.reductionContexts))
  val vxissqs = xissParams.zip(contextCounts).map { case (q, counts) =>
    Module(new IssueQueue(q.depth, counts.sum)).suggestName(s"vxissq_${q.name}")
  }

  val vxus = xissParams.map(_.seqs.map(s => Module(new ExecutionUnit(s.fus, s.name)).suggestName(s"vxu${s.name}")))
  val flat_vxus = vxus.flatten
  val maxPipeDepth = flat_vxus.map(_.maxPipeDepth).max

  val vls = Module(new LoadSequencer)
  val vss = Module(new StoreSequencer)
  val reductionSeqs = (0 until vParams.reductionContexts).map { i =>
    Module(new SpecialSequencer(all_supported_insns, i, vParams.reductionContexts, interleave)).suggestName(s"vps$i")
  }
  val vps = reductionSeqs.head // Only context zero handles permutations and VMU index/mask streams.
  val executionContexts = xissParams.zip(contextCounts).map { case (q, counts) =>
    q.seqs.zip(counts).map { case (s, count) =>
      (0 until count).map { c =>
        Module(new ExecuteSequencer(s.insns, maxPipeDepth, s.fus.size,
          ordinaryOnly = count > 1 && c == 0, reductionOnly = count > 1 && c > 0))
          .suggestName(s"vxs${s.name}_$c")
      }
    }
  }
  val vxs = executionContexts.map(_.flatten)

  val allSeqs = Seq(vls, vss) ++ reductionSeqs ++ vxs.flatten
  val allIssQs = Seq(vlissq, vsissq, vpissq) ++ vxissqs

  val flat_vxs = vxs.flatten
  val contextsByFu = executionContexts.flatten
  val fuBySequencer = contextsByFu.zip(flat_vxus).flatMap { case (seqs, xu) => seqs.map(_ => xu) }
  val representativeByFu = contextsByFu.map(seqs => flat_vxs.indexOf(seqs.head))
  io.executionPerf := 0.U.asTypeOf(io.executionPerf)
  def perfCount(condition: Bool): UInt = {
    val counter = RegInit(0.U(64.W))
    when(condition) { counter := counter + 1.U }
    counter
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
  val reductionAllocated = RegInit(VecInit.fill(vParams.reductionContexts)(false.B))
  val reductionChoice = PriorityEncoderOH(VecInit(reductionAllocated.map(!_)).asUInt)
  val dispatchReduction = !vdq.io.deq.bits.vmu && dis_ctrl.bool(Reduction)
  val reductionBlocked = interleave.B && dispatchReduction && reductionAllocated.asUInt.andR
  io.executionPerf.reductionFull := perfCount(vdq.io.deq.valid && reductionBlocked)
  for ((seq, c) <- reductionSeqs.zipWithIndex) {
    when(seq.io.acc_done && seq.io.busy) { reductionAllocated(c) := false.B }
    when(interleave.B && vdq.io.deq.fire && dispatchReduction && reductionChoice(c)) {
      reductionAllocated(c) := true.B
    }
  }

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
    vxissq.io.enq.bits.renv1 := dis_ctrl.bool(ReadsVS1) && !dis_ctrl.bool(Reduction)
    vxissq.io.enq.bits.renv2 := dis_ctrl.bool(ReadsVS2)
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
        !dispatchReduction || reductionChoice(c)
      } else seq match {
        case s: ExecuteSequencer if s.reductionOnly => reductionChoice(c - 1)
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

  // ======================================
  // Connect reads to VRF

  val vrf = Module(new RegisterAccess(flat_vxs.size, maxPipeDepth, specialReads = 3,
    reductionContexts = vParams.reductionContexts))
  pendingWrites := vrf.io.pendingWrites
  io.debugVrf.foreach(_ := vrf.io.debug.get)
  io.vrfPerf := vrf.io.perf
  io.special <> vrf.io.special

  vrf.io.vls.rvm.req <> vls.io.rvm
  vrf.io.vss.rvd.req <> vss.io.rvd
  vrf.io.vss.rvm.req <> vss.io.rvm
  vss.io.rvd_resp := vrf.io.vss.rvd.resp
  vss.io.rvm_resp := vrf.io.vss.rvm.resp
  for ((seq, i) <- reductionSeqs.zipWithIndex) {
    vrf.io.vps(i).rvs2.req <> seq.io.rvs2
    vrf.io.vps(i).rvm.req <> seq.io.rvm
    seq.io.acc_init_resp := vrf.io.vps(i).rvs2.resp
    seq.io.rvm_resp := vrf.io.vps(i).rvm.resp
    if (i != 0) { seq.io.iss.ready := false.B }
  }

  val executeCandidates = scala.collection.mutable.ArrayBuffer.empty[DecoupledIO[ExecuteMicroOpWithData]]
  for (i <- 0 until flat_vxs.size) {
    val vxs = flat_vxs(i)
    val vxu = fuBySequencer(i)

    vrf.io.vxs(i).rvs1.req <> vxs.io.rvs1.req
    vrf.io.vxs(i).rvs2.req <> vxs.io.rvs2.req
    vrf.io.vxs(i).rvd.req <> vxs.io.rvd.req
    vrf.io.vxs(i).rvm.req <> vxs.io.rvm.req
    vxs.io.rvs1.resp := vrf.io.vxs(i).rvs1.resp
    vxs.io.rvs2.resp := vrf.io.vxs(i).rvs2.resp
    vxs.io.rvd.resp := vrf.io.vxs(i).rvd.resp
    vxs.io.rvm.resp := vrf.io.vxs(i).rvm.resp
    vrf.io.vxs(i).prefetch := vxs.io.prefetch

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

    when (vxs_iss.acc) {
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
        vxu_iss.wmask := Mux(vxs_iss.acc_copy, eewByteMask(vxs_iss.vd_eew), foldMask)
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

  // Contexts share one arithmetic unit and one physical result channel.
  // Reserve output time across all contexts, including different latencies.
  for (((seqs, xu), factory) <- contextsByFu.zip(flat_vxus).zip(xissParams.flatMap(_.seqs))) {
    val arb = Module(new SharedExecutionArbiter(seqs.size, maxPipeDepth, xu.nFUs))
    for ((seq, c) <- seqs.zipWithIndex) { arb.io.in(c) <> executeCandidates(flat_vxs.indexOf(seq)) }
    xu.io.iss <> arb.io.out
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
  io.index_access.idx   := frontend_rindex.resp >> ((io.index_access.eidx << io.index_access.eew)(dLenOffBits-1,0) << 3) & eewBitMask(io.index_access.eew)

  val vm_busy = Wire(Bool())
  frontend_rmask.req.valid    := io.mask_access.valid && !vm_busy
  frontend_rmask.req.bits.eg  := getEgId(0.U, io.mask_access.eidx, 0.U, true.B)
  frontend_rmask.req.bits.oldest := false.B
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

  val perm_vxs = flat_vxs.filter(_.usesPerm)
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
    if (vxs.usesAcc) {
      for (seq <- reductionSeqs) {
        when (vxs.io.vat === seq.io.vat && vxs.io.busy && seq.io.busy) {
          seq.io.acc_data.ready := vxs.io.acc_ready && vxs.io.iss.ready
          seq.io.acc_slot := vxs.io.acc_slot
          seq.io.acc_other_slot := vxs.io.acc_other_slot
          seq.io.acc_merge := vxs.io.acc_merge
          vxs.io.acc_valid := seq.io.acc_data.valid
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

  val writeCompletion = Module(new WriteCompletionTracker(vParams.vatSz, flat_vxus.size + 1))
  writeCompletion.io.tails(0).valid := vls.io.iss.fire && vls.io.iss.bits.tail
  writeCompletion.io.tails(0).bits := vls.io.iss.bits.vat
  for ((xu, i) <- flat_vxus.zipWithIndex) { writeCompletion.io.tails(i + 1) := xu.io.completed }
  writeCompletion.io.pendingWrites := pendingWrites.map { h =>
    Mux(h.valid, UIntToOH(h.bits.vat, 1 << vParams.vatSz), 0.U)
  }.reduce(_ | _)
  clearVat(writeCompletion.io.release.valid, writeCompletion.io.release.bits)
  clearVat(vss.io.iss.fire && vss.io.iss.bits.tail, vss.io.iss.bits.vat)
  flat_vxus.foreach(_ => clearVat(false.B, 0.U))

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
