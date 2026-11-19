package evu

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.{CoreModule, TileVisibilityNodeKey}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.{ExecuteContextLayout, ExecuteSequencer, IntegerContextRules, SharedExecutionArbiter}
import xiangshan.evu.common._
import xiangshan.evu.exu._

class EvuIntegerContextHarness(implicit p: Parameters) extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val command = Vec(2, Flipped(Decoupled(new BackendIssueInst)))
    val olderWrites = Input(Vec(2, UInt(egsTotal.W)))
    val olderReads = Input(Vec(2, UInt(egsTotal.W)))
    val source = Input(Vec(2, UInt(dLen.W)))
    val readReady = Input(Vec(2, Bool()))
    val issueReady = Input(Vec(2, Bool()))
    val writeReady = Input(Vec(2, Bool()))
    val eligible = Output(Vec(2, Bool()))
    val issue = Output(Valid(UInt(vParams.vatSz.W)))
    val output = Output(Valid(new VectorWrite(dLen)))
    val writeVat = Output(UInt(vParams.vatSz.W))
    val probeReads = Input(UInt(32.W))
    val probeWrites = Input(UInt(32.W))
    val probeOlderReads = Input(UInt(32.W))
    val probeOlderWrites = Input(UInt(32.W))
    val raw = Output(Bool())
    val destination = Output(Bool())
  })
  val factories = Seq(IntegerPipeFactory, BitwisePipeFactory, PermuteUnitFactory)
  val insns = VXSequencerParams("int", factories).insns
  val unit = Module(new ExecutionUnit(factories, "integer_context_test"))
  val seqs = (0 until 2).map(c => Module(new ExecuteSequencer(insns, unit.maxPipeDepth,
    factories.size, ordinaryOnly = true, restrictedInteger = c == 1)))
  val arb = Module(new SharedExecutionArbiter(2, unit.maxPipeDepth, factories.size))
  unit.io.iss <> arb.io.out
  unit.io.iter_write.ready := true.B
  unit.io.scalar_write.ready := true.B
  unit.io.shared_fp_req.ready := true.B
  unit.io.shared_fp_resp := 0.U.asTypeOf(unit.io.shared_fp_resp)
  io.issue.valid := unit.io.iss.fire
  io.issue.bits := unit.io.iss.bits.vat
  io.output := unit.io.pipe_write
  io.writeVat := unit.io.pipeWriteVat
  io.raw := IntegerContextRules.raw(io.probeReads, io.probeOlderWrites)
  io.destination := IntegerContextRules.destination(io.probeWrites, io.probeOlderReads, io.probeOlderWrites)
  for ((seq, c) <- seqs.zipWithIndex) {
    val other = seqs(1 - c).io.seq_hazard
    val older = other.valid && cqOlder(other.bits.vat, seq.io.vat, 16.U)
    val pipeline = unit.io.pipe_hazards.map(h => Mux(h.valid && h.bits.vat =/= seq.io.vat,
      h.bits.eg_oh, 0.U(egsTotal.W))).foldLeft(0.U(egsTotal.W))(_ | _)
    seq.io.dis <> io.command(c)
    seq.io.dis_stall := false.B
    seq.io.older_reads := io.olderReads(c) | Mux(older, other.bits.rintent, 0.U)
    seq.io.older_writes := io.olderWrites(c) | Mux(older, other.bits.wintent, 0.U) | pipeline
    seq.io.vat_head := 0.U
    seq.io.acc_valid := false.B
    seq.io.acc_return := false.B
    seq.io.lookahead := 0.U.asTypeOf(seq.io.lookahead)
    seq.io.pipe_write_req.available := io.writeReady(c) && arb.io.available(c)
    seq.io.vgu.slide_req.ready := false.B
    seq.io.vgu.slide_data := 0.U
    seq.io.vgu.gather_eidx.valid := false.B
    seq.io.vgu.gather_eidx.bits := 0.U
    for (read <- Seq(seq.io.rvs1, seq.io.rvs2, seq.io.rvd, seq.io.rvm)) {
      read.req.ready := io.readReady(c)
      read.resp := io.source(c)
    }
    io.eligible(c) := seq.accepts(io.command(c).bits)
    val in = arb.io.in(c)
    in.valid := seq.io.iss.valid && io.issueReady(c)
    seq.io.iss.ready := in.ready && io.issueReady(c)
    in.bits := 0.U.asTypeOf(in.bits)
    in.bits.viewAsSupertype(new ExecuteMicroOp(factories.size)) := seq.io.iss.bits
    val op = seq.io.iss.bits
    in.bits.rvs1_data := Mux(op.use_scalar_rvs1, dLenSplat(op.scalar, op.rvs1_eew), seq.io.rvs1_data)
    in.bits.rvs2_data := Mux(op.use_zero_rvs2, 0.U,
      Mux(op.use_slide_rvs2, op.slide_data, seq.io.rvs2_data))
    in.bits.rvd_data := seq.io.rvd_data
    in.bits.wmask := op.eidx_mask
  }
}

class EvuIntegerContextShell(implicit p: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("integer-context-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuIntegerContextShellImp(this)
}

class EvuIntegerContextShellImp(outer: EvuIntegerContextShell)(implicit p: Parameters) extends LazyModuleImp(outer) {
  val harness = Module(new EvuIntegerContextHarness()(p.alterPartial { case TileVisibilityNodeKey => outer.client }))
  val io = IO(chiselTypeOf(harness.io))
  io <> harness.io
}

class EvuIntegerContextSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private def parameters: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
    case MonitorsEnabled => false
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }, 4096, 512, 512, VectorParams.lgvParams.copy(alignedSlide = true, singleGroupSlide = true))

  private def clear(data: Data): Unit = data match {
    case v: Bool => v.poke(false.B)
    case v: UInt => v.poke(0.U)
    case v: SInt => v.poke(0.S)
    case v: Vec[_] => v.foreach(clear)
    case v: Bundle => v.elements.values.foreach(clear)
  }
  private def pack(value: Int): BigInt = (0 until 16).foldLeft(BigInt(0))((b, i) => b | (BigInt(value) << (i * 32)))
  private def command(op: BackendIssueInst, function: BigInt, source: Int, destination: Int,
      vat: Int, length: Int = 16, index: Int = 0): Unit = {
    clear(op)
    op.bits.poke(((function << 26) | (BigInt(1) << 25) | (source << 20) |
      (index << 15) | (3 << 12) | (destination << 7) | 0x57).U)
    op.vconfig.vl.poke(length.U)
    op.vconfig.vtype.vsew.poke(2.U)
    op.renv2.poke(true.B)
    op.wvd.poke(true.B)
    op.vat.poke(vat.U)
  }

  behavior of "Restricted second integer context"

  it should "use two contexts without introducing a reduction owner" in {
    val layout = ExecuteContextLayout("int", true, 2, 2)
    layout.size shouldBe 2
    layout.reductionOwner(0) shouldBe None
    layout.reductionOwner(1) shouldBe None
  }

  it should "accept only the short whitelist and classify RAW WAR and WAW independently" in {
    implicit val p: Parameters = parameters
    test(LazyModule(new EvuIntegerContextShell).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.command.foreach { c => clear(c.bits); c.valid.poke(false.B) }
      clear(dut.io.olderReads)
      clear(dut.io.olderWrites)
      dut.io.source.foreach(_.poke(0.U))
      dut.io.readReady.foreach(_.poke(false.B))
      dut.io.issueReady.foreach(_.poke(false.B))
      dut.io.writeReady.foreach(_.poke(false.B))
      dut.io.probeReads.poke(2.U)
      dut.io.probeWrites.poke(4.U)
      dut.io.probeOlderReads.poke(0.U)
      dut.io.probeOlderWrites.poke(0.U)
      dut.io.raw.expect(false.B)
      dut.io.destination.expect(false.B)
      dut.io.probeOlderWrites.poke(2.U)
      dut.io.raw.expect(true.B)
      dut.io.destination.expect(false.B)
      dut.io.probeOlderWrites.poke(4.U)
      dut.io.raw.expect(false.B)
      dut.io.destination.expect(true.B)
      dut.io.probeOlderWrites.poke(0.U)
      dut.io.probeOlderReads.poke(4.U)
      dut.io.destination.expect(true.B)
      for (function <- Seq(OPIFunct6.rgather, OPIFunct6.slideup, OPIFunct6.slidedown)) {
        command(dut.io.command(1).bits, function.litValue, 8, 18, 5)
        dut.io.eligible(1).expect(true.B)
      }
      val op = dut.io.command(1).bits
      val mutations: Seq[() => Unit] = Seq(
        () => op.vconfig.vl.poke(17.U),
        () => op.vconfig.vl.poke(0.U),
        () => op.vstart.poke(1.U),
        () => op.vconfig.vtype.vsew.poke(1.U),
        () => op.vconfig.vtype.vlmul_mag.poke(1.U),
        () => op.vconfig.vtype.vlmul_sign.poke(true.B),
        () => op.bits.poke(((OPIFunct6.rgather.litValue << 26) | (8 << 20) | (18 << 7) | 0x57).U),
        () => command(op, OPIFunct6.slidedown.litValue, 8, 18, 5, index = 1))
      for (mutate <- mutations) {
        command(op, OPIFunct6.rgather.litValue, 8, 18, 5)
        mutate()
        dut.io.eligible(1).expect(false.B)
      }
    }
  }

  it should "issue independent work while C0 waits and retain RAW WAR WAW protection" in {
    implicit val p: Parameters = parameters
    test(LazyModule(new EvuIntegerContextShell).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.command.foreach { c => clear(c.bits); c.valid.poke(false.B) }
      clear(dut.io.olderReads)
      clear(dut.io.olderWrites)
      dut.io.probeReads.poke(0.U)
      dut.io.probeWrites.poke(0.U)
      dut.io.probeOlderReads.poke(0.U)
      dut.io.probeOlderWrites.poke(0.U)
      dut.io.readReady.foreach(_.poke(true.B))
      dut.io.issueReady.foreach(_.poke(true.B))
      dut.io.writeReady.foreach(_.poke(true.B))
      for (hazard <- Seq("none", "raw", "war", "waw")) {
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        command(dut.io.command(0).bits, OPIFunct6.rgather.litValue, 8, 18, 4)
        val source = if (hazard == "raw") 18 else 21
        val destination = if (hazard == "war") 8 else if (hazard == "waw") 18 else 19
        command(dut.io.command(1).bits, OPIFunct6.rgather.litValue, source, destination, 5, length = 8)
        dut.io.source(0).poke(pack(7).U)
        dut.io.source(1).poke(pack(12).U)
        dut.io.olderWrites(0).poke((BigInt(1) << 64).U)
        dut.io.command.foreach(_.valid.poke(true.B))
        dut.clock.step()
        dut.io.command.foreach(_.valid.poke(false.B))
        var second = false
        var written = false
        for (_ <- 0 until 8) {
          if (dut.io.issue.valid.peek().litToBoolean) {
            dut.io.issue.bits.expect(5.U)
            second = true
          }
          if (dut.io.output.valid.peek().litToBoolean && dut.io.writeVat.peek().litValue == 5) {
            dut.io.output.bits.mask.expect(((BigInt(1) << 256) - 1).U)
            written = true
          }
          dut.clock.step()
        }
        second shouldBe (hazard == "none")
        written shouldBe (hazard == "none")
        dut.io.olderWrites(0).poke(0.U)
        val seen = scala.collection.mutable.Set.empty[Int]
        for (_ <- 0 until 20) {
          if (dut.io.issue.valid.peek().litToBoolean) seen += dut.io.issue.bits.peek().litValue.toInt
          dut.clock.step()
        }
        seen should contain (4)
        if (hazard != "none") seen should contain (5)
      }
    }
  }

  it should "preserve a long gather cache across an interleaved short gather" in {
    implicit val p: Parameters = parameters
    test(LazyModule(new EvuIntegerContextShell).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.command.foreach { c => clear(c.bits); c.valid.poke(false.B) }
      clear(dut.io.olderReads)
      clear(dut.io.olderWrites)
      dut.io.probeReads.poke(0.U)
      dut.io.probeWrites.poke(0.U)
      dut.io.probeOlderReads.poke(0.U)
      dut.io.probeOlderWrites.poke(0.U)
      dut.io.source(0).poke(pack(7).U)
      dut.io.source(1).poke(pack(12).U)
      dut.io.readReady.foreach(_.poke(true.B))
      dut.io.writeReady.foreach(_.poke(true.B))
      dut.io.issueReady(0).poke(true.B)
      dut.io.issueReady(1).poke(false.B)
      command(dut.io.command(0).bits, OPIFunct6.rgather.litValue, 8, 18, 4, length = 128)
      command(dut.io.command(1).bits, OPIFunct6.rgather.litValue, 21, 19, 5)
      dut.io.command(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.command(0).valid.poke(false.B)
      var first = false
      var cycles = 0
      while (!first && cycles < 20) {
        first = dut.io.issue.valid.peek().litToBoolean
        dut.clock.step()
        cycles += 1
      }
      first shouldBe true
      dut.io.issueReady(0).poke(false.B)
      dut.io.command(1).valid.poke(true.B)
      dut.clock.step()
      dut.io.command(1).valid.poke(false.B)
      dut.io.issueReady(1).poke(true.B)
      var short = false
      for (_ <- 0 until 8) {
        if (dut.io.output.valid.peek().litToBoolean && dut.io.writeVat.peek().litValue == 5) {
          dut.io.output.bits.data.expect(pack(12).U)
          short = true
        }
        dut.clock.step()
      }
      short shouldBe true
      dut.io.issueReady(0).poke(true.B)
      var remaining = 0
      for (_ <- 0 until 40) {
        if (dut.io.output.valid.peek().litToBoolean && dut.io.writeVat.peek().litValue == 4) {
          dut.io.output.bits.data.expect(pack(7).U)
          remaining += 1
        }
        dut.clock.step()
      }
      remaining shouldBe 7
    }
  }

  it should "preserve compress destination and count across short gather and slide operations" in {
    implicit val p: Parameters = parameters
    test(new PermuteUnit).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      clear(dut.io.iss)
      clear(dut.io.pipe)
      dut.io.scalar_write.ready.poke(true.B)
      val op = dut.io.pipe(0).bits
      def micro(function: BigInt, funct3: Int, destination: Int, head: Boolean, tail: Boolean): Unit = {
        clear(op)
        op.funct6.poke(function.U)
        op.funct3.poke(funct3.U)
        op.head.poke(head.B)
        op.tail.poke(tail.B)
        op.rvs2_eew.poke(2.U)
        op.wvd_eg.poke((destination * 8).U)
        op.vl.poke(16.U)
        op.wmask.poke(((BigInt(1) << 64) - 1).U)
        dut.io.pipe(0).valid.poke(true.B)
      }
      micro(OPMFunct6.compress.litValue, 2, 18, head = true, tail = false)
      op.rvs1_data.poke(1.U)
      op.rvs2_data.poke(pack(7).U)
      dut.io.write.valid.expect(true.B)
      dut.io.write.bits.eg.expect((18 * 8).U)
      dut.io.write.bits.mask.expect(BigInt("ffffffff", 16).U)
      dut.clock.step()

      micro(OPIFunct6.rgather.litValue, 3, 19, head = true, tail = true)
      op.rvs2_data.poke(pack(12).U)
      dut.io.write.bits.data.expect(pack(12).U)
      dut.clock.step()
      micro(OPIFunct6.slideup.litValue, 3, 20, head = true, tail = true)
      dut.clock.step()

      micro(OPMFunct6.compress.litValue, 2, 18, head = false, tail = true)
      op.eidx.poke(1.U)
      op.rvs1_data.poke(2.U)
      op.rvs2_data.poke(pack(7).U)
      dut.io.write.valid.expect(true.B)
      dut.io.write.bits.eg.expect((18 * 8).U)
      dut.io.write.bits.mask.expect((BigInt("ffffffff", 16) << 32).U)
      dut.io.write.bits.data.expect(pack(7).U)
    }
  }
}
