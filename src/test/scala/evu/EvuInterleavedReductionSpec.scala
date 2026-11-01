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
import xiangshan.evu.backend.{ExecuteSequencer, SharedExecutionArbiter, SpecialSequencer, VectorBackend}
import xiangshan.evu.common._
import xiangshan.evu.exu._

/** Exercise production sequencers/FMA without elaborating unrelated backend FUs.
  * Source responses are a test fixture, not a replacement VRF implementation.
  */
class EvuSharedReductionHarness(implicit p: Parameters) extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val command = Vec(2, Flipped(Decoupled(new BackendIssueInst)))
    val sourceReady = Input(Vec(2, Bool()))
    val result = Output(Vec(2, UInt(32.W)))
    val completed = Output(Vec(2, Bool()))
    val switched = Output(UInt(32.W))
    val flags = Output(UInt(5.W))
  })
  val factory = SIMDFPFMAFactory(4, true)
  val insns = VXSequencerParams("fp_fma", Seq(factory)).insns
  val execute = Seq.fill(2)(Module(new ExecuteSequencer(insns, 4, 1, reductionOnly = true)))
  val accumulators = (0 until 2).map(i => Module(new SpecialSequencer(insns, i, 2, true)))
  val arb = Module(new SharedExecutionArbiter(2, 4, 1))
  val fu = Module(new ExecutionUnit(Seq(factory), "shared_reduction_test"))
  fu.io.iss <> arb.io.out
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
  val previousVat = RegEnable(fu.io.iss.bits.vat, fu.io.iss.fire)
  val previousValid = RegNext(fu.io.iss.fire, false.B)
  val switched = RegInit(0.U(32.W))
  when(fu.io.iss.fire && previousValid && previousVat =/= fu.io.iss.bits.vat) {
    switched := switched + 1.U
  }
  io.switched := switched
  for (((seq, acc), i) <- execute.zip(accumulators).zipWithIndex) {
    io.command(i).ready := seq.io.dis.ready && acc.io.dis.ready
    seq.io.dis.valid := io.command(i).valid && acc.io.dis.ready
    acc.io.dis.valid := io.command(i).valid && seq.io.dis.ready
    seq.io.dis.bits := io.command(i).bits
    acc.io.dis.bits := io.command(i).bits
    seq.io.dis_stall := false.B
    acc.io.dis_stall := false.B
    seq.io.older_reads := 0.U
    seq.io.older_writes := 0.U
    acc.io.older_reads := 0.U
    acc.io.older_writes := 0.U
    seq.io.vat_head := 0.U
    acc.io.vat_head := 0.U
    seq.io.vgu.slide_req.ready := true.B
    seq.io.vgu.slide_data := 0.U
    seq.io.vgu.gather_eidx.valid := false.B
    seq.io.vgu.gather_eidx.bits := 0.U
    for (read <- Seq(seq.io.rvs1, seq.io.rvs2, seq.io.rvd, seq.io.rvm)) {
      read.req.ready := io.sourceReady(i)
      read.resp := Fill(2, (if (i == 0) 0x3f800000L else 0x40000000L).U(32.W))
    }
    acc.io.rvs2.ready := io.sourceReady(i)
    acc.io.rvm.ready := io.sourceReady(i)
    acc.io.acc_init_resp := 0.U
    acc.io.rvm_resp := 0.U
    acc.io.iss.ready := false.B
    acc.io.acc_slot := seq.io.acc_slot
    acc.io.acc_other_slot := seq.io.acc_other_slot
    acc.io.acc_merge := seq.io.acc_merge
    acc.io.acc_data.ready := seq.io.acc_ready && seq.io.iss.ready
    seq.io.acc_valid := acc.io.acc_data.valid && acc.io.busy && seq.io.vat === acc.io.vat
    acc.io.acc_done := seq.io.iss.fire && seq.io.iss.bits.acc && seq.io.iss.bits.tail
    acc.io.acc_fu_resp.valid := fu.io.acc_write.valid && fu.io.accWriteVat === acc.io.vat
    acc.io.acc_fu_resp.bits := fu.io.acc_write.bits
    acc.io.acc_fu_resp_slot := fu.io.acc_write_slot
    seq.io.pipe_write_req.available := arb.io.available(i)
    val candidate = arb.io.in(i)
    candidate.valid := seq.io.iss.valid
    seq.io.iss.ready := candidate.ready
    candidate.bits := 0.U.asTypeOf(candidate.bits)
    candidate.bits.viewAsSupertype(new ExecuteMicroOp(1)) := seq.io.iss.bits
    candidate.bits.rvs1_data := acc.io.acc_data.bits
    candidate.bits.rvs1_elem := acc.io.acc_data.bits
    candidate.bits.rvs2_data := seq.io.rvs2_data
    candidate.bits.rvs2_elem := seq.io.rvs2_data
    candidate.bits.wmask := seq.io.iss.bits.eidx_mask
    candidate.bits.rmask := Fill(dLenB, 1.U(1.W))
    candidate.bits.rvs1_eew := seq.io.iss.bits.vd_eew
    when(seq.io.iss.bits.acc_merge) {
      candidate.bits.wmask := Fill(dLenB, 1.U(1.W))
      candidate.bits.rvs2_data := acc.io.acc_other_data
      candidate.bits.rvs2_elem := acc.io.acc_other_data
      candidate.bits.rvs2_eew := seq.io.iss.bits.vd_eew
    }.elsewhen(seq.io.iss.bits.acc_fold) {
      val folded = VecInit.tabulate(log2Ceil(dLenB))(j =>
        acc.io.acc_data.bits((dLen >> j) - 1, dLen >> (j + 1)))(seq.io.iss.bits.acc_fold_id)
      val mask = VecInit.tabulate(log2Ceil(dLenB))(j =>
        ((BigInt(1) << (dLenB >> (j + 1))) - 1).U(dLenB.W))(seq.io.iss.bits.acc_fold_id)
      candidate.bits.wmask := mask
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

class EvuSharedReductionShell(implicit p: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("shared-fma-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuSharedReductionShellImp(this)
}

class EvuSharedReductionShellImp(outer: EvuSharedReductionShell)(implicit p: Parameters)
    extends LazyModuleImp(outer) {
  val harness = Module(new EvuSharedReductionHarness()(p.alterPartial { case TileVisibilityNodeKey => outer.client }))
  val io = IO(chiselTypeOf(harness.io))
  io <> harness.io
}

class EvuInterleavedBackendHarness(implicit p: Parameters) extends CoreModule with HasVectorParams {
  val backend = Module(new VectorBackend)
  val io = IO(new Bundle {
    val instruction = Flipped(Decoupled(new VectorIssueInst))
    val load = Flipped(Decoupled(UInt(dLen.W)))
    val vrf = Output(Vec(egsTotal, UInt(dLen.W)))
    val release = Output(Vec(nRelease, Valid(UInt(vParams.vatSz.W))))
    val flags = Output(Vec(nRelease, UInt(5.W)))
    val perf = Output(new EvuExecutionPerf)
    val busy = Output(Bool())
  })
  backend.io.dis <> io.instruction
  // Restrict test decode to load, sum and add so Treadle can discard unused
  // div/exp/int logic while retaining the production queues and shared FMA.
  val source = io.instruction.bits.bits
  backend.io.dis.bits.bits := Mux(io.instruction.bits.vmu,
    Cat(0.U(6.W), true.B, 0.U(5.W), source(19, 15), 6.U(3.W), source(11, 7), 7.U(7.W)),
    Cat(Mux(io.instruction.bits.opff6 === OPFFunct6.fredusum,
      OPFFunct6.fredusum.asUInt, OPFFunct6.fadd.asUInt), true.B,
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
  backend.io.special.read.foreach { r =>
    r.req.valid := false.B
    r.req.bits := 0.U.asTypeOf(r.req.bits)
  }
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
  io.busy := backend.io.busy
}

class EvuInterleavedBackendShell(implicit p: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("reduction-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuInterleavedBackendShellImp(this)
}

class EvuInterleavedBackendShellImp(outer: EvuInterleavedBackendShell)(implicit p: Parameters)
    extends LazyModuleImp(outer) {
  val harness = Module(new EvuInterleavedBackendHarness()(p.alterPartial { case TileVisibilityNodeKey => outer.client }))
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

  it should "interleave production sequencers on a shared FMA under operand backpressure" in {
    implicit val p: Parameters = EvuVectorParameters(base, 512, 64, 64,
      VectorParams.lgvParams.copy(reductionContexts = 2, interleaveReductions = true))
    test(LazyModule(new EvuSharedReductionShell).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
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

  it should "retain production 1RW VRF and shared arithmetic units in memory-only elaboration" in {
    implicit val p: Parameters = base
    val ir = ChiselStage.emitCHIRRTL(LazyModule(new EvuSaturnMemoryTagHarness(
      EvuParams(enable = true, enableDifftest = true))).module)
    ir should include ("vxsfp_fma_1")
    ir should include ("vxsfp_fma_2")
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
