package evu

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import _root_.circt.stage.ChiselStage
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.{CoreModule, TileVisibilityNodeKey}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.{ExecuteSequencer, ReductionFirstGroup, SharedExecutionArbiter, SpecialSequencer, VectorBackend}
import xiangshan.evu.common._
import xiangshan.evu.exu._

/** Exercise production sequencers/FMA without elaborating unrelated backend FUs.
  * Source responses are a test fixture, not a replacement VRF implementation.
  */
class EvuSharedReductionHarness(maximum: Boolean = false, operandInputs: Boolean = false,
    controlled: Boolean = false)(implicit p: Parameters)
    extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val command = Vec(2, Flipped(Decoupled(new BackendIssueInst)))
    val sourceReady = Input(Vec(2, Bool()))
    val result = Output(Vec(2, UInt(32.W)))
    val completed = Output(Vec(2, Bool()))
    val switched = Output(UInt(32.W))
    val flags = Output(UInt(5.W))
    val merges = Output(Vec(2, UInt(32.W)))
    val folds = Output(Vec(2, UInt(32.W)))
    val source = Input(Vec(2, UInt(dLen.W)))
    val seed = Input(Vec(2, UInt(32.W)))
    val mask = Input(Vec(2, UInt(dLen.W)))
    val seedReady = Input(Vec(2, Bool()))
    val olderReads = Input(Vec(2, UInt(egsTotal.W)))
    val olderWrites = Input(Vec(2, UInt(egsTotal.W)))
    val issueReady = Input(Bool())
    val writeReady = Input(Bool())
    val issues = Output(Vec(2, UInt(32.W)))
    val firstLoads = Output(Vec(2, UInt(32.W)))
    val directs = Output(Vec(2, UInt(32.W)))
    val returns = Output(Vec(2, UInt(32.W)))
    val ownerBusy = Output(Vec(2, Bool()))
    val seedReads = Output(Vec(2, UInt(32.W)))
    val seedE0Read = Output(Vec(2, Bool()))
  })
  val factory: FunctionalUnitFactory = if (maximum) FPCmpFactory else SIMDFPFMAFactory(4, true)
  val insns = VXSequencerParams("fp_fma", Seq(factory)).insns
  val fu = Module(new ExecutionUnit(Seq(factory), "shared_reduction_test"))
  val directSum = !maximum && dLen == 512
  val execute = Seq.fill(2)(Module(new ExecuteSequencer(insns, fu.maxPipeDepth, 1,
    reductionOnly = true, directSumLoad = directSum)))
  val accumulators = (0 until 2).map(i => Module(new SpecialSequencer(insns, i, 2, true, directSum)))
  val arb = Module(new SharedExecutionArbiter(2, fu.maxPipeDepth, 1,
    prioritizeCritical = true, directSumLoad = directSum))
  val selected = Wire(Decoupled(chiselTypeOf(arb.io.out.bits)))
  val allowIssue = if (controlled) io.issueReady else true.B
  selected.valid := arb.io.out.valid && allowIssue
  selected.bits := arb.io.out.bits
  arb.io.out.ready := selected.ready && allowIssue
  val firstLoad = if (directSum) ReductionFirstGroup.route(selected, fu.io.iss, accumulators) else {
    fu.io.iss <> selected
    false.B
  }
  arb.directLoadFire.foreach(_ := firstLoad)
  execute.foreach(_.directLoadFire.foreach(_ := firstLoad))
  fu.io.iter_write.ready := true.B
  fu.io.scalar_write.ready := true.B
  fu.io.shared_fp_req.ready := true.B
  fu.io.shared_fp_resp.valid := false.B
  fu.io.shared_fp_resp.bits := 0.U.asTypeOf(fu.io.shared_fp_resp.bits)
  val results = RegInit(VecInit.fill(2)(0.U(32.W)))
  val completed = RegInit(VecInit.fill(2)(false.B))
  io.result := results
  io.completed := completed
  val flags = RegInit(0.U(5.W))
  when(fu.io.set_fflags.valid) { flags := flags | fu.io.set_fflags.bits }
  io.flags := flags
  val merges = RegInit(VecInit.fill(2)(0.U(32.W)))
  io.merges := merges
  val folds = RegInit(VecInit.fill(2)(0.U(32.W)))
  io.folds := folds
  val issues = RegInit(VecInit.fill(2)(0.U(32.W)))
  val directs = RegInit(VecInit.fill(2)(0.U(32.W)))
  val returns = RegInit(VecInit.fill(2)(0.U(32.W)))
  val seedReads = RegInit(VecInit.fill(2)(0.U(32.W)))
  io.issues := issues
  io.directs := directs
  io.returns := returns
  io.seedReads := seedReads
  for (context <- 0 until 2) {
    io.firstLoads(context) := Counter(firstLoad && selected.bits.vat === (context + 1).U, 256)._1
    when(fu.io.iss.fire && fu.io.iss.bits.vat === (context + 1).U) {
      issues(context) := issues(context) + 1.U
      when(fu.io.iss.bits.acc_direct) { directs(context) := directs(context) + 1.U }
    }
    when(fu.io.acc_write.valid && fu.io.accWriteVat === (context + 1).U) {
      returns(context) := returns(context) + 1.U
    }
    when(fu.io.iss.fire && fu.io.iss.bits.acc_merge && fu.io.iss.bits.vat === (context + 1).U) {
      merges(context) := merges(context) + 1.U
    }
    when(fu.io.iss.fire && fu.io.iss.bits.acc_fold && fu.io.iss.bits.vat === (context + 1).U) {
      folds(context) := folds(context) + 1.U
    }
  }
  val previousVat = RegEnable(fu.io.iss.bits.vat, fu.io.iss.fire)
  val previousValid = RegNext(fu.io.iss.fire, false.B)
  val switched = RegInit(0.U(32.W))
  when(fu.io.iss.fire && previousValid && previousVat =/= fu.io.iss.bits.vat) {
    switched := switched + 1.U
  }
  io.switched := switched
  for (((seq, acc), i) <- execute.zip(accumulators).zipWithIndex) {
    val dispatched = WireInit(io.command(i).bits)
    if (maximum) {
      // This fixture exercises only FP32 min/max. Constant decode fields let
      // Treadle prune unrelated FP16, mask-comparison and permutation logic.
      val bits = io.command(i).bits.bits
      dispatched.bits := Cat(Mux(io.command(i).bits.opff6 === OPFFunct6.fredmin,
        OPFFunct6.fredmin.asUInt, OPFFunct6.fredmax.asUInt), bits(25, 15), OPFVV, bits(11, 0))
      dispatched.vconfig.vtype.vsew := 2.U
      dispatched.reduction := true.B
      dispatched.scalar_to_vd0 := false.B
      dispatched.wide_vd := false.B
      dispatched.wide_vs2 := false.B
      dispatched.writes_mask := false.B
      dispatched.reads_vs1_mask := false.B
      dispatched.reads_vs2_mask := false.B
      dispatched.renv1 := false.B
      dispatched.renv2 := true.B
      dispatched.renvd := false.B
      dispatched.renvm := !io.command(i).bits.vm
      dispatched.wvd := true.B
    }
    val direct = VectorFastPaths.singleGroupMinMax(dispatched, dLen,
      vParams.fp32MinMaxTree && vParams.singleGroupMinMax)
    io.command(i).ready := seq.io.dis.ready && (direct || acc.io.dis.ready)
    seq.io.dis.valid := io.command(i).valid && (direct || acc.io.dis.ready)
    acc.io.dis.valid := io.command(i).valid && seq.io.dis.ready && !direct
    seq.io.dis.bits := dispatched
    acc.io.dis.bits := dispatched
    seq.io.dis_stall := false.B
    acc.io.dis_stall := false.B
    seq.io.older_reads := (if (controlled) io.olderReads(i) else 0.U)
    seq.io.older_writes := (if (controlled) io.olderWrites(i) else 0.U)
    acc.io.older_reads := 0.U
    acc.io.older_writes := (if (controlled) io.olderWrites(i) else 0.U)
    seq.io.vat_head := 0.U
    acc.io.vat_head := 0.U
    seq.io.vgu.slide_req.ready := true.B
    seq.io.vgu.slide_data := 0.U
    seq.io.vgu.gather_eidx.valid := false.B
    seq.io.vgu.gather_eidx.bits := 0.U
    for ((read, port) <- Seq(seq.io.rvs1, seq.io.rvs2, seq.io.rvd, seq.io.rvm).zipWithIndex) {
      read.req.ready := (if (controlled && port == 0) io.seedReady(i) else io.sourceReady(i))
      read.resp := (if (operandInputs) {
        if (port == 0) io.seed(i) else if (port == 3) io.mask(i) else io.source(i)
      } else if (port == 0) 0.U else Fill(dLen / 32, (if (i == 0) 0x3f800000L else 0x40000000L).U(32.W)))
    }
    acc.io.rvs2.ready := (if (controlled) io.seedReady(i) else io.sourceReady(i))
    acc.io.rvm.ready := io.sourceReady(i)
    acc.io.acc_init_resp := (if (operandInputs) io.seed(i) else 0.U)
    io.seedE0Read(i) := acc.io.e0Read
    when(acc.io.rvs2.fire || seq.io.rvs1.req.fire) { seedReads(i) := seedReads(i) + 1.U }
    acc.io.rvm_resp := 0.U
    acc.io.iss.ready := false.B
    acc.io.acc_slot := seq.io.acc_slot
    acc.io.acc_other_slot := seq.io.acc_other_slot
    acc.io.acc_merge := seq.io.acc_merge
    acc.io.acc_data.ready := seq.io.acc_ready && seq.io.iss.ready
    seq.io.acc_valid := acc.io.acc_data.valid && acc.io.busy && seq.io.vat === acc.io.vat
    seq.io.acc_return := acc.io.acc_return
    seq.io.lookahead := 0.U.asTypeOf(seq.io.lookahead)
    acc.io.acc_done := seq.io.iss.fire && seq.io.iss.bits.acc && seq.io.iss.bits.tail &&
      !seq.io.iss.bits.acc_direct && acc.io.busy && seq.io.vat === acc.io.vat
    acc.io.acc_fu_resp.valid := fu.io.acc_write.valid && fu.io.accWriteVat === acc.io.vat
    acc.io.acc_fu_resp.bits := fu.io.acc_write.bits
    acc.io.acc_fu_resp_slot := fu.io.acc_write_slot
    seq.io.pipe_write_req.available := (if (controlled) io.writeReady else true.B)
    when(seq.io.iss.fire) {
      assert(seq.io.pipe_write_req.fire === seq.io.iss.bits.tail)
      assert(seq.io.pipe_write_req.request === seq.io.iss.bits.tail)
    }
    io.ownerBusy(i) := acc.io.busy
    val candidate = arb.io.in(i)
    candidate.valid := seq.io.iss.valid
    seq.io.iss.ready := candidate.ready
    candidate.bits := 0.U.asTypeOf(candidate.bits)
    candidate.bits.viewAsSupertype(new ExecuteMicroOp(1)) := seq.io.iss.bits
    candidate.bits.rvs1_data := acc.io.acc_data.bits
    candidate.bits.rvs1_elem := acc.io.acc_data.bits
    candidate.bits.rvs2_data := seq.io.rvs2_data
    candidate.bits.rvs2_elem := seq.io.rvs2_data
    val vm = if (operandInputs) FillInterleaved(4,
      (io.mask(i) >> seq.io.iss.bits.eidx)(dLen / 32 - 1, 0)) else Fill(dLenB, 1.U(1.W))
    candidate.bits.wmask := seq.io.iss.bits.eidx_mask & Mux(seq.io.iss.bits.vm, Fill(dLenB, 1.U(1.W)), vm)
    candidate.bits.rmask := Fill(dLenB, 1.U(1.W))
    candidate.bits.rvs1_eew := seq.io.iss.bits.vd_eew
    when(seq.io.iss.bits.acc_direct) {
      candidate.bits.wmask := 15.U
      candidate.bits.rvs1_data := seq.io.rvs1_data(31, 0)
      candidate.bits.rvs1_elem := seq.io.rvs1_data(31, 0)
    }.elsewhen(seq.io.iss.bits.acc_merge) {
      candidate.bits.wmask := Fill(dLenB, 1.U(1.W))
      candidate.bits.rvs2_data := acc.io.acc_other_data
      candidate.bits.rvs2_elem := acc.io.acc_other_data
      candidate.bits.rvs2_eew := seq.io.iss.bits.vd_eew
    }.elsewhen(seq.io.iss.bits.acc_fold) {
      val folded = VecInit.tabulate(log2Ceil(dLenB))(j =>
        acc.io.acc_data.bits((dLen >> j) - 1, dLen >> (j + 1)))(seq.io.iss.bits.acc_fold_id)
      val mask = VecInit.tabulate(log2Ceil(dLenB))(j =>
        ((BigInt(1) << (dLenB >> (j + 1))) - 1).U(dLenB.W))(seq.io.iss.bits.acc_fold_id)
      candidate.bits.wmask := Mux(seq.io.iss.bits.acc_tree || seq.io.iss.bits.acc_empty, 15.U, mask)
      candidate.bits.rvs1_data := folded
      candidate.bits.rvs1_elem := folded
      candidate.bits.rvs2_data := acc.io.acc_data.bits
      candidate.bits.rvs2_elem := acc.io.acc_data.bits
      candidate.bits.rvs2_eew := seq.io.iss.bits.vd_eew
    }
    when(fu.io.pipe_write.valid && fu.io.completed.valid && fu.io.completed.bits === (i + 1).U) {
      results(i) := fu.io.pipe_write.bits.data(31, 0)
      completed(i) := true.B
    }
  }
}

class EvuSharedReductionShell(val maximum: Boolean = false, val operandInputs: Boolean = false,
    val controlled: Boolean = false)(implicit p: Parameters)
    extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("shared-fma-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuSharedReductionShellImp(this)
}

class EvuSharedReductionShellImp(outer: EvuSharedReductionShell)(implicit p: Parameters)
    extends LazyModuleImp(outer) {
  val harness = Module(new EvuSharedReductionHarness(outer.maximum, outer.operandInputs, outer.controlled)(p.alterPartial {
    case TileVisibilityNodeKey => outer.client
  }))
  val io = IO(chiselTypeOf(harness.io))
  io <> harness.io
}

class EvuInterleavedBackendHarness(allowMinMax: Boolean = false)(implicit p: Parameters) extends CoreModule with HasVectorParams {
  val backend = Module(new VectorBackend)
  val io = IO(new Bundle {
    val instruction = Flipped(Decoupled(new VectorIssueInst))
    val load = Flipped(Decoupled(UInt(dLen.W)))
    val vrf = Output(Vec(egsTotal, UInt(dLen.W)))
    val release = Output(Vec(nRelease, Valid(UInt(vParams.vatSz.W))))
    val flags = Output(Vec(nRelease, UInt(5.W)))
    val perf = Output(new EvuExecutionPerf)
    val vrfPerf = Output(new xiangshan.evu.backend.VrfPerf)
    val busy = Output(Bool())
  })
  backend.io.dis <> io.instruction
  // Restrict test decode to load, sum and add so Treadle can discard unused
  // div/exp/int logic while retaining the production queues and shared FMA.
  val source = io.instruction.bits.bits
  val function = if (allowMinMax) {
    MuxCase(OPFFunct6.fadd.asUInt, Seq(OPFFunct6.fredusum, OPFFunct6.fredmin, OPFFunct6.fredmax).map(f =>
      (io.instruction.bits.opff6 === f) -> f.asUInt))
  } else Mux(io.instruction.bits.opff6 === OPFFunct6.fredusum,
    OPFFunct6.fredusum.asUInt, OPFFunct6.fadd.asUInt)
  backend.io.dis.bits.bits := Mux(io.instruction.bits.vmu,
    Cat(0.U(6.W), true.B, 0.U(5.W), source(19, 15), 6.U(3.W), source(11, 7), 7.U(7.W)),
    Cat(function, true.B,
      source(24, 15), VectorConsts.OPFVV, source(11, 7), VectorConsts.opcVector))
  backend.io.dis.bits.vconfig.vtype.vsew := 2.U
  backend.io.dis.bits.mop := 0.U
  backend.io.dis.bits.emul := 0.U
  backend.io.vmu.lresp.valid := io.load.valid
  backend.io.vmu.lresp.bits.data := io.load.bits
  backend.io.vmu.lresp.bits.debug_id := 0.U
  io.load.ready := backend.io.vmu.lresp.ready
  backend.io.vmu.sdata.ready := true.B
  backend.io.vmu.mask_pop.valid := false.B
  backend.io.vmu.mask_pop.bits := 0.U.asTypeOf(backend.io.vmu.mask_pop.bits)
  backend.io.vmu.index_pop.valid := false.B
  backend.io.vmu.index_pop.bits := 0.U.asTypeOf(backend.io.vmu.index_pop.bits)
  backend.io.index_access.valid := false.B
  backend.io.index_access.vrs := 0.U
  backend.io.index_access.eidx := 0.U
  backend.io.index_access.eew := 0.U
  backend.io.mask_access.valid := false.B
  backend.io.mask_access.eidx := 0.U
  backend.io.scalar_resp.ready := true.B
  backend.io.fp_req.ready := true.B
  backend.io.fp_resp.valid := false.B
  backend.io.fp_resp.bits := 0.U.asTypeOf(backend.io.fp_resp.bits)
  backend.io.vat_head := 0.U
  backend.io.vat_tail := 16.U
  io.vrf := backend.io.debugVrf.get
  io.release := backend.io.vat_release
  io.flags := backend.io.completion_fflags
  io.perf := backend.io.executionPerf
  io.vrfPerf := backend.io.vrfPerf
  io.busy := backend.io.busy
}

class EvuInterleavedBackendShell(val allowMinMax: Boolean = false)(implicit p: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("reduction-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuInterleavedBackendShellImp(this)
}

class EvuInterleavedBackendShellImp(outer: EvuInterleavedBackendShell)(implicit p: Parameters)
    extends LazyModuleImp(outer) {
  val harness = Module(new EvuInterleavedBackendHarness(outer.allowMinMax)(p.alterPartial { case TileVisibilityNodeKey => outer.client }))
  val io = IO(chiselTypeOf(harness.io))
  io <> harness.io
}

class EvuInterleavedReductionSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val base = Parameters.empty.alterPartial {
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }
  behavior of "Shared-FMA reduction contexts"

  it should "fall back when even one lane is zero, subnormal, infinite or NaN" in {
    test(new Module {
      val io = IO(new Bundle { val data = Input(UInt(512.W)); val normal = Output(Bool()) })
      io.normal := ReductionFirstGroup.allNormal(io.data)
    }).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val base = (0 until 16).map(i => BigInt(if ((i & 1) == 0) 0x80800000L else 0x7f7fffffL) << (i*32)).sum
      dut.io.data.poke(base.U); dut.io.normal.expect(true.B)
      for (lane <- 0 until 16; bits <- Seq(0L, 0x80000000L, 1L, 0x807fffffL, 0x7f800000L,
          0xff800000L, 0x7f800001L, 0x7fc00000L)) {
        dut.io.data.poke(((base & ~(BigInt(0xffffffffL) << (lane*32))) | (BigInt(bits) << (lane*32))).U)
        dut.io.normal.expect(false.B)
      }
    }
  }

  it should "interleave production sequencers on a shared FMA under operand backpressure" in {
    implicit val p: Parameters = EvuVectorParameters(base, 512, 64, 64,
      VectorParams.lgvParams.copy(reductionContexts = 2, interleaveReductions = true))
    test(LazyModule(new EvuSharedReductionShell()).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      def clear(x: Data): Unit = x match {
        case b: Bool => b.poke(false.B)
        case u: UInt => u.poke(0.U)
        case s: SInt => s.poke(0.S)
        case v: Vec[_] => v.foreach(clear)
        case b: Bundle => b.elements.values.foreach(clear)
      }
      for (i <- 0 until 2) {
        val c = dut.io.command(i)
        clear(c.bits)
        c.bits.bits.poke(((OPFFunct6.fredusum.litValue << 26) | (BigInt(1) << 25) |
          ((i + 1) << 20) | (1 << 12) | ((i + 3) << 7) | 0x57).U)
        c.bits.vconfig.vl.poke(16.U)
        c.bits.vconfig.vtype.vsew.poke(2.U)
        c.bits.vat.poke((i + 1).U)
        c.bits.reduction.poke(true.B)
        c.bits.renv1.poke(true.B)
        c.bits.renv2.poke(true.B)
        c.bits.wvd.poke(true.B)
        c.valid.poke(true.B)
        dut.io.sourceReady(i).poke(true.B)
        c.ready.expect(true.B)
      }
      dut.clock.step()
      dut.io.command.foreach(_.valid.poke(false.B))
      for (cycle <- 0 until 120) {
        dut.io.sourceReady(0).poke((cycle % 7 != 0).B)
        dut.io.sourceReady(1).poke((cycle % 5 != 0).B)
        dut.clock.step()
      }
      dut.io.completed.foreach(_.expect(true.B))
      dut.io.result(0).expect(0x41800000L.U) // 16 * 1.0
      dut.io.result(1).expect(0x42000000L.U) // 16 * 2.0
      dut.io.flags.expect(0.U)
      dut.io.switched.peek().litValue should be > BigInt(0)
    }
  }

  it should "direct-load slot zero only on its first full normal group with an actual positive-zero seed" in {
    implicit val p: Parameters = EvuVectorParameters(base, 4096, 512, 512,
      VectorParams.lgvParams.copy(reductionContexts = 2, interleaveReductions = true))
    test(LazyModule(new EvuSharedReductionShell(operandInputs = true, controlled = true)).module)
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        val unit = BigInt(0x3f800000L)
        val ones = (0 until 16).map(i => unit << (32*i)).sum
        for ((seed, length, unmasked, lowLane, loads, expected, flags) <- Seq(
            (0L, 16, true, unit, 1, 16f, 0),
            (0x80000000L, 16, true, unit, 0, 16f, 0),
            (0x3f800000L, 16, true, unit, 0, 17f, 0),
            (0L, 16, true, BigInt(0), 0, 15f, 0),
            (0L, 16, true, BigInt(0x7f800001L), 0, Float.NaN, 16),
            (0L, 16, false, unit, 0, 16f, 0),
            (0L, 15, true, unit, 0, 15f, 0),
            (0L, 80, true, unit, 4, 80f, 0))) {
          dut.io.command.foreach(_.valid.poke(false.B))
          dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
          val cmd = dut.io.command(0)
          def clear(x: Data): Unit = x match {
            case b: Bool => b.poke(false.B)
            case u: UInt => u.poke(0.U)
            case s: SInt => s.poke(0.S)
            case v: Vec[_] => v.foreach(clear)
            case b: Bundle => b.elements.values.foreach(clear)
          }
          clear(cmd.bits)
          dut.io.olderReads(0).poke(0.U); dut.io.olderWrites(0).poke(0.U)
          dut.io.issueReady.poke(true.B); dut.io.seedReady(0).poke(false.B)
          dut.io.writeReady.poke(false.B)
          cmd.bits.bits.poke(((OPFFunct6.fredusum.litValue << 26) | (BigInt(if (unmasked) 1 else 0) << 25) |
            (1 << 20) | (2 << 15) | (1 << 12) | (3 << 7) | 0x57).U)
          cmd.bits.vconfig.vl.poke(length.U); cmd.bits.vconfig.vtype.vsew.poke(2.U)
          cmd.bits.vat.poke(1.U); cmd.bits.reduction.poke(true.B); cmd.bits.wvd.poke(true.B)
          cmd.bits.renv1.poke(true.B); cmd.bits.renv2.poke(true.B); cmd.bits.renvm.poke((!unmasked).B)
          dut.io.seed(0).poke(seed.U); dut.io.source(0).poke((ones - unit + lowLane).U)
          dut.io.mask(0).poke(((BigInt(1) << length) - 1).U); dut.io.sourceReady(0).poke(false.B)
          cmd.valid.poke(true.B); cmd.ready.expect(true.B); dut.clock.step(); cmd.valid.poke(false.B)
          dut.clock.step(2); dut.io.firstLoads(0).expect(0.U); dut.io.sourceReady(0).poke(true.B)
          dut.io.seedReady(0).poke(true.B)
          // Every internal return (including merge/fold) advances with the VRF bank denied.
          var waitForTail = 0
          while (dut.io.folds(0).peek().litValue < 3 && waitForTail < 120) {
            dut.clock.step(); waitForTail += 1
          }
          dut.clock.step(6)
          dut.io.folds(0).expect(3.U); dut.io.completed(0).expect(false.B)
          dut.io.firstLoads(0).expect(loads.U)
          dut.io.writeReady.poke(true.B)
          var cycles = 0
          while (!dut.io.completed(0).peek().litToBoolean && cycles < 150) { dut.clock.step(); cycles += 1 }
          dut.io.completed(0).expect(true.B); dut.io.firstLoads(0).expect(loads.U)
          val groups = (length + 15) / 16
          dut.io.issues(0).expect((groups + groups.min(4) - 1 + 4 - loads).U)
          dut.io.flags.expect(flags.U)
          dut.io.result(0).expect((java.lang.Float.floatToIntBits(expected).toLong & 0xffffffffL).U)
        }
      }
  }

  it should "reduce short FP32 sums and maxima without merging unused accumulator slots" in {
    for (maximum <- Seq(false, true); tree <- Seq(false, true)) {
      implicit val parameters: Parameters = EvuVectorParameters(base, 4096, 512, 512,
        VectorParams.lgvParams.copy(reductionContexts = 2, interleaveReductions = true,
          fp32MinMaxTree = tree))
      test(LazyModule(new EvuSharedReductionShell(maximum)).module)
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          def clear(data: Data): Unit = data match {
            case value: Bool => value.poke(false.B)
            case value: UInt => value.poke(0.U)
            case value: SInt => value.poke(0.S)
            case values: Vec[_] => values.foreach(clear)
            case bundle: Bundle => bundle.elements.values.foreach(clear)
          }
          for (length <- Seq(1, 13, 16, 17, 32, 33, 48, 49, 64, 128)) {
            dut.io.command.foreach(_.valid.poke(false.B))
            dut.reset.poke(true.B)
            dut.clock.step(2)
            dut.reset.poke(false.B)
            for (context <- 0 until 2) {
              val command = dut.io.command(context)
              clear(command.bits)
              val function = if (maximum) OPFFunct6.fredmax else OPFFunct6.fredusum
              command.bits.bits.poke(((function.litValue << 26) | (BigInt(1) << 25) |
                ((context + 1) << 20) | (1 << 12) | ((context + 3) << 7) | 0x57).U)
              command.bits.vconfig.vl.poke(length.U)
              command.bits.vconfig.vtype.vsew.poke(2.U)
              command.bits.vat.poke((context + 1).U)
              command.bits.reduction.poke(true.B)
              command.bits.renv1.poke(true.B)
              command.bits.renv2.poke(true.B)
              command.bits.wvd.poke(true.B)
              command.valid.poke(true.B)
              dut.io.sourceReady(context).poke(true.B)
              command.ready.expect(true.B)
            }
            dut.clock.step()
            dut.io.command.foreach(_.valid.poke(false.B))
            for (cycle <- 0 until 200) {
              dut.io.sourceReady(0).poke((cycle % 7 != 0).B)
              dut.io.sourceReady(1).poke((cycle % 5 != 0).B)
              dut.clock.step()
            }
            dut.io.completed.foreach(_.expect(true.B))
            for (context <- 0 until 2) {
              val expected = ((if (maximum) 1 else length) * (context + 1)).toFloat
              dut.io.result(context).expect((java.lang.Float.floatToRawIntBits(expected).toLong & 0xffffffffL).U)
              dut.io.merges(context).expect((math.min(4, (length + 15) / 16) - 1).U)
              dut.io.folds(context).expect((if (maximum && tree) 1 else 4).U)
              val loaded = dut.io.firstLoads(context).peek().litValue.toInt
              loaded should be <= (if (maximum) 0 else (length / 16).min(4))
              if (!maximum && length == 64 && context == 1)
                dut.io.firstLoads.map(_.peek().litValue).sum should be > BigInt(0)
              if (!maximum) dut.io.issues(context).expect(((length + 15) / 16 +
                math.min(4, (length + 15) / 16) - 1 + 4 - loaded).U)
            }
            dut.io.flags.expect(0.U)
          }
        }
    }
  }

  it should "retain production 1RW VRF and shared arithmetic units in memory-only elaboration" in {
    implicit val p: Parameters = base
    val ir = ChiselStage.emitCHIRRTL(LazyModule(new EvuSaturnMemoryTagHarness(
      EvuParams(enable = true, enableDifftest = true))).module)
    ir should include ("vxsfp_fma_1")
    ir should include ("vxsfp_fma_2")
    ir should include ("vxsfp_fma_3")
    ir should not include "vxsfp_cmp_3"
    "\\binst vxufp_fma of ".r.findAllIn(ir).length shouldBe 1
    ir should include ("sram_array_1p64x64m8")
  }

  it should "interleave two FP32 sums on one FMA and preserve a dependent scalar add" in {
    if (!sys.env.get("EVU_FULL_BACKEND_TREADLE").contains("1")) {
      cancel("Full-backend Treadle is opt-in; production elaboration and the focused shared-FMA test run by default")
    }
    implicit val p: Parameters = EvuVectorParameters(base, 512, 64, 64,
      VectorParams.lgvParams.copy(debugVrf = true, reductionContexts = 2,
        interleaveReductions = true, issStructure = VectorIssueStructure.SplitFP, vlissqEntries = 4,
        vpissqEntries = 4, vdqEntries = 4, vrfBanking = 8))
    test(LazyModule(new EvuInterleavedBackendShell).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      def clear(x: Data): Unit = x match {
        case b: Bool => b.poke(false.B)
        case u: UInt => u.poke(0.U)
        case s: SInt => s.poke(0.S)
        case v: Vec[_] => v.foreach(clear)
        case b: Bundle => b.elements.values.foreach(clear)
      }
      def raw(x: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(x).toLong & 0xffffffffL)
      clear(dut.io.instruction.bits)
      dut.io.instruction.valid.poke(false.B)
      dut.io.load.valid.poke(false.B)
      dut.io.load.bits.poke(0.U)
      dut.clock.step(270)
      val completed = scala.collection.mutable.Set.empty[Int]
      def tick(): Unit = {
        for (i <- 0 until dut.io.release.length if dut.io.release(i).valid.peek().litToBoolean) {
          val vat = dut.io.release(i).bits.peek().litValue.toInt
          completed += vat
          dut.io.flags(i).expect(0.U)
        }
        dut.clock.step()
      }
      def send(bits: BigInt, vat: Int, vl: Int): Unit = {
        clear(dut.io.instruction.bits)
        dut.io.instruction.bits.bits.poke(bits.U)
        dut.io.instruction.bits.vconfig.vl.poke(vl.U)
        dut.io.instruction.bits.vconfig.vtype.vsew.poke(2.U)
        dut.io.instruction.bits.vat.poke(vat.U)
        dut.io.instruction.valid.poke(true.B)
        var n = 0
        while (!dut.io.instruction.ready.peek().litToBoolean && n < 200) { tick(); n += 1 }
        dut.io.instruction.ready.expect(true.B)
        tick()
        dut.io.instruction.valid.poke(false.B)
      }
      for ((reg, vat, value) <- Seq((0, 0, 0f), (1, 1, 1f), (2, 2, 2f))) {
        send((BigInt(1) << 25) | (6 << 12) | (reg << 7) | 7, vat, 16)
        dut.io.load.bits.poke((raw(value) | (raw(value) << 32)).U)
        dut.io.load.valid.poke(true.B)
        var accepted = 0
        var cycles = 0
        while (accepted < 8 && cycles < 500) {
          if (dut.io.load.ready.peek().litToBoolean) accepted += 1
          tick()
          cycles += 1
        }
        accepted shouldBe 8
        dut.io.load.valid.poke(false.B)
        while (!completed(vat) && cycles < 600) { tick(); cycles += 1 }
        completed(vat) shouldBe true
      }
      val sum = OPFFunct6.fredusum.litValue
      val add = OPFFunct6.fadd.litValue
      def fp(f6: BigInt, dst: Int, a: Int, b: Int): BigInt =
        (f6 << 26) | (BigInt(1) << 25) | (b << 20) | (a << 15) | (1 << 12) | (dst << 7) | 0x57
      send(fp(sum, 3, 0, 1), 3, 16)
      send(fp(sum, 4, 0, 2), 4, 16)
      send(fp(add, 5, 3, 4), 5, 1)
      var cycles = 0
      while (!completed(5) && cycles < 600) { tick(); cycles += 1 }
      completed should contain allOf (3, 4, 5)
      (dut.io.vrf(3 * 8).peek().litValue & 0xffffffffL) shouldBe raw(16f)
      (dut.io.vrf(4 * 8).peek().litValue & 0xffffffffL) shouldBe raw(32f)
      (dut.io.vrf(5 * 8).peek().litValue & 0xffffffffL) shouldBe raw(48f)
      dut.io.perf.fmaInterleave.peek().litValue should be > BigInt(0)
      dut.io.busy.expect(false.B)
    }
  }
}
