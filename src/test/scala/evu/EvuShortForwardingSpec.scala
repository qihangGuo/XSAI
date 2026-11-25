package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.{CoreModule, TileVisibilityNodeKey}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.{ExecuteSequencer, StoreSequencer, VectorResultForwarding}
import xiangshan.evu.common._
import xiangshan.evu.exu._

class EvuShortReadHarness(factory: FunctionalUnitFactory, operand: Int)(implicit p: Parameters)
    extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new BackendIssueInst))
    val result = Input(Valid(new xiangshan.evu.backend.VectorForwardResult))
    val olderWrites = Input(UInt(egsTotal.W))
    val olderReads = Input(UInt(egsTotal.W))
    val issueReady = Input(Bool())
    val amaxDisjoint = Option.when(factory == FPCmpFactory)(Input(Bool()))
    val amaxHeadIssued = Option.when(factory == FPCmpFactory)(Output(Bool()))
    val masks = Output(Vec(3, UInt(dLen.W)))
    val forwarded = Output(Vec(3, Bool()))
    val read = Output(Vec(3, Bool()))
    val issue = Output(Bool())
    val data = Output(UInt(dLen.W))
  })
  val insns = VXSequencerParams("short-read-test", Seq(factory)).insns
  val seq = Module(new ExecuteSequencer(insns, 4, 1, resultForwarding = true))
  seq.amaxWaw.foreach { port =>
    port.onlyDisjoint := io.amaxDisjoint.get
    io.amaxHeadIssued.get := port.headIssued
  }
  seq.io.dis <> io.command
  seq.io.dis_stall := false.B
  seq.io.older_writes := io.olderWrites
  seq.io.older_reads := io.olderReads
  seq.io.vat_head := 0.U
  seq.io.acc_valid := true.B
  seq.io.acc_return := false.B
  seq.io.lookahead := 0.U.asTypeOf(seq.io.lookahead)
  seq.io.iss.ready := io.issueReady
  seq.io.pipe_write_req.available := true.B
  seq.io.vgu.slide_req.ready := false.B
  seq.io.vgu.slide_data := 0.U
  seq.io.vgu.gather_eidx.valid := false.B
  seq.io.vgu.gather_eidx.bits := 0.U
  seq.io.rvm.req.ready := false.B
  seq.io.rvm.resp := 0.U
  val bypass = Module(new VectorResultForwarding(1, 3, 0, partial = true))
  bypass.io.tail := 0.U
  bypass.io.results(0) := io.result
  for ((read, i) <- Seq(seq.io.rvs1, seq.io.rvs2, seq.io.rvd).zipWithIndex) {
    val port = seq.io.forward.get(i)
    bypass.io.reads(i).valid := port.eligible
    bypass.io.reads(i).bits.eg := read.req.bits.eg
    bypass.io.reads(i).bits.vat := seq.io.vat
    bypass.io.reads(i).bits.mask := port.mask
    port.available := bypass.io.out(i).valid
    read.req.ready := bypass.io.out(i).valid || (i != operand).B
    read.resp := bypass.io.out(i).bits
    io.masks(i) := port.mask
    io.forwarded(i) := port.available
    io.read(i) := read.req.valid
  }
  io.issue := seq.io.iss.valid
  io.data := Seq(seq.io.rvs1_data, seq.io.rvs2_data, seq.io.rvd_data)(operand)
}

class EvuShortReadShell(factory: FunctionalUnitFactory, operand: Int)(implicit p: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("short-read-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuShortReadShellImp(this, factory, operand)
}

class EvuShortReadShellImp(outer: EvuShortReadShell, factory: FunctionalUnitFactory, operand: Int)(implicit p: Parameters)
    extends LazyModuleImp(outer) {
  val harness = Module(new EvuShortReadHarness(factory, operand)(p.alterPartial { case TileVisibilityNodeKey => outer.client }))
  val io = IO(chiselTypeOf(harness.io))
  io <> harness.io
}

class EvuShortStoreHarness()(implicit p: Parameters) extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new BackendIssueInst))
    val result = Input(Valid(new xiangshan.evu.backend.VectorForwardResult))
    val olderWrites = Input(UInt(egsTotal.W))
    val ready = Input(Bool())
    val mask = Output(UInt(dLen.W))
    val forwarded = Output(Bool())
    val issue = Output(Bool())
    val storeMask = Output(UInt(mLenB.W))
    val data = Output(UInt(dLen.W))
  })
  val seq = Module(new StoreSequencer(resultForwarding = true))
  seq.io.dis <> io.command
  seq.io.dis_stall := false.B
  seq.io.older_writes := io.olderWrites
  seq.io.older_reads := 0.U
  seq.io.vat_head := 0.U
  seq.io.iss.ready := io.ready
  seq.io.rvm.ready := false.B
  seq.io.rvm_resp := 0.U
  val bypass = Module(new VectorResultForwarding(1, 1, 0, partial = true))
  bypass.io.tail := 0.U
  bypass.io.results(0) := io.result
  bypass.io.reads(0).valid := seq.io.forward.get.eligible
  bypass.io.reads(0).bits.eg := seq.io.rvd.bits.eg
  bypass.io.reads(0).bits.vat := seq.io.vat
  bypass.io.reads(0).bits.mask := seq.io.forward.get.mask
  seq.io.forward.get.available := bypass.io.out(0).valid
  seq.io.rvd.ready := bypass.io.out(0).valid
  seq.io.rvd_resp := bypass.io.out(0).bits
  io.mask := seq.io.forward.get.mask
  io.forwarded := bypass.io.out(0).valid
  io.issue := seq.io.iss.valid
  io.storeMask := seq.io.iss.bits.eidx_mask
  io.data := seq.io.rvd_data
}

class EvuShortStoreShell(implicit p: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("short-store-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuShortStoreShellImp(this)
}

class EvuShortStoreShellImp(outer: EvuShortStoreShell)(implicit p: Parameters) extends LazyModuleImp(outer) {
  val harness = Module(new EvuShortStoreHarness()(p.alterPartial { case TileVisibilityNodeKey => outer.client }))
  val io = IO(chiselTypeOf(harness.io))
  io <> harness.io
}

class EvuShortProducerHarness(reduction: Boolean, blockAmax: Boolean = false)(implicit p: Parameters) extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val issue = Input(Bool())
    val read = Input(Bool())
    val source = Input(UInt(512.W))
    val needed = Input(UInt(512.W))
    val beat = Option.when(blockAmax)(Input(UInt(2.W)))
    val quarter = Option.when(blockAmax)(Input(UInt(2.W)))
    val write = Output(Valid(new VectorWrite(512)))
    val forwardable = Output(Bool())
    val bypass = Output(Valid(UInt(512.W)))
  })
  val unit = Module(new ExecutionUnit(Seq(if (reduction) FPCmpFactory else FPXX8Factory), "short_producer"))
  unit.io.iss.valid := io.issue
  unit.io.iss.bits := 0.U.asTypeOf(unit.io.iss.bits)
  val op = unit.io.iss.bits
  op.fu_sel := 1.U
  op.funct3 := (if (reduction) VectorConsts.OPFVV else VectorConsts.OPIVV)
  op.funct6 := (if (reduction) OPFFunct6.fredmax.asUInt else 0x39.U)
  op.rvs1_data := 0.U
  op.rvs2_data := io.source
  op.rvs1_eew := 2.U
  op.rvs2_eew := 2.U
  op.vd_eew := (if (reduction) 2.U else 0.U)
  op.vm := true.B
  op.vl := 16.U
  op.eidx_mask := ~0.U(64.W)
  op.wmask := ~0.U(64.W)
  op.pipe_depth := (if (reduction) 3.U else 1.U)
  op.vat := 1.U
  op.wvd_eg := 8.U
  op.tail := true.B
  op.acc := reduction.B
  op.acc_fold := reduction.B
  op.acc_tree := reduction.B
  if (blockAmax) {
    require(reduction) // select the CMP unit
    op.funct3 := VectorConsts.OPIVV
    op.funct6 := 0x3e.U
    op.vl := 64.U
    op.rs1 := io.quarter.get
    op.eidx := io.beat.get << 4
    op.head := io.beat.get === 0.U
    op.tail := io.beat.get === 3.U
    op.acc := false.B
    op.acc_fold := false.B
    op.acc_tree := false.B
  }
  unit.io.iter_write.ready := true.B
  unit.io.scalar_write.ready := true.B
  unit.io.shared_fp_req.ready := true.B
  unit.io.shared_fp_resp := 0.U.asTypeOf(unit.io.shared_fp_resp)
  val bypass = Module(new VectorResultForwarding(1, 1, 0, partial = true, retainCycles = 4))
  bypass.io.tail := 0.U
  bypass.io.results(0).valid := unit.io.pipe_write.valid && unit.io.pipeWriteForwardable
  bypass.io.results(0).bits.eg := unit.io.pipe_write.bits.eg
  bypass.io.results(0).bits.data := unit.io.pipe_write.bits.data
  bypass.io.results(0).bits.mask := unit.io.pipe_write.bits.mask
  bypass.io.results(0).bits.vat := unit.io.pipeWriteVat
  bypass.io.accepted.get(0) := unit.io.pipe_write.valid
  bypass.io.reads(0).valid := io.read
  bypass.io.reads(0).bits.eg := 8.U
  bypass.io.reads(0).bits.vat := 2.U
  bypass.io.reads(0).bits.mask := io.needed
  io.bypass := bypass.io.out(0)
  io.write := unit.io.pipe_write
  io.forwardable := unit.io.pipeWriteForwardable
}

class EvuShortForwardingSpec extends AnyFlatSpec with ChiselScalatestTester {
  private val base = Parameters.empty.alterPartial {
    case MonitorsEnabled => false
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }
  private implicit val p: Parameters = EvuVectorParameters(base, 4096, 512, 512,
    VectorParams.lgvParams.copy(shortResultForwarding = true, singleGroupSlide = true, fp32MinMaxTree = true))
  private val full = (BigInt(1) << 512) - 1
  private def bytes(n: Int): BigInt = (BigInt(1) << (8 * n)) - 1
  private def clear(data: Data): Unit = data match {
    case b: Bool => b.poke(false.B)
    case u: UInt => u.poke(0.U)
    case s: SInt => s.poke(0.S)
    case v: Vec[_] => v.foreach(clear)
    case b: Bundle => b.elements.values.foreach(clear)
  }

  behavior of "Mask-covered recent result forwarding"

  it should "keep consecutive BLOCK_AMAX rows separate in the shared tree" in {
    test(new EvuShortProducerHarness(reduction = true, blockAmax = true)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val io = dut.io
      io.read.poke(false.B)
      io.needed.poke(0.U)
      def fp(n: Int): BigInt = BigInt(java.lang.Float.floatToRawIntBits(n.toFloat).toLong & 0xffffffffL)
      val quarters = Seq(3, 1)
      for (t <- 0 until 12) {
        val beat = t % 4
        io.issue.poke((t < 8).B)
        io.quarter.get.poke(quarters((t / 4) % 2).U)
        io.beat.get.poke(beat.U)
        io.source.poke((fp(-(t + 1)) << (32 * beat)).U)
        val writing = t == 7 || t == 11 // four stages after each tail
        io.write.valid.expect(writing.B)
        if (writing) {
          val row = (t - 7) / 4
          val shift = 128 * quarters(row)
          val words = (0 until 4).map(i => fp(4 * row + i + 1) << (32 * i)).sum
          io.write.bits.eg.expect(8.U)
          io.write.bits.mask.expect((bytes(16) << shift).U)
          io.write.bits.data.expect((words << shift).U)
        }
        dut.clock.step()
      }
    }
  }

  it should "waive only a disjoint BLOCK_AMAX WAW and count its first accepted beat" in {
    test(LazyModule(new EvuShortReadShell(FPCmpFactory, 0)).module)
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        val io = dut.io
        clear(io.command.bits)
        clear(io.result)
        val src = BigInt(1) << 16 // v2, EG 0
        val dst = BigInt(1) << 24 // v3, EG 0
        io.issueReady.poke(false.B)
        io.olderWrites.poke(dst.U)
        io.olderReads.poke(0.U)
        io.amaxDisjoint.get.poke(false.B)
        def dispatch(f6: Int, f3: Int): Unit = {
          val c = io.command.bits
          c.bits.poke(((BigInt(f6) << 26) | (1 << 25) | (2 << 20) | (1 << 15) |
            (f3 << 12) | (3 << 7) | 0x57).U)
          c.vat.poke(2.U)
          c.vconfig.vl.poke(64.U)
          c.vconfig.vtype.vsew.poke(2.U)
          c.renv2.poke(true.B)
          c.wvd.poke(true.B)
          io.command.valid.poke(true.B)
          io.command.ready.expect(true.B)
          dut.clock.step()
          io.command.valid.poke(false.B)
        }
        dispatch(0x3e, 0)
        io.issue.expect(false.B) // Same quarter, unknown writer, or old row still collecting.
        io.amaxDisjoint.get.poke(true.B)
        io.olderWrites.poke((dst | src).U)
        io.issue.expect(false.B) // Source RAW still wins.
        io.olderWrites.poke(dst.U)
        io.olderReads.poke(dst.U)
        io.issue.expect(false.B) // Destination WAR still wins.
        io.olderReads.poke(0.U)
        io.issue.expect(true.B)
        io.amaxHeadIssued.get.expect(false.B)
        io.issueReady.poke(true.B)
        for (beat <- 0 until 4) {
          io.issue.expect(true.B)
          io.amaxHeadIssued.get.expect((beat == 0).B)
          dut.clock.step()
        }
        io.issue.expect(false.B)
        dispatch(OPFFunct6.fsgnjx.litValue.toInt, VectorConsts.OPFVV.litValue.toInt)
        io.issue.expect(false.B) // Ordinary CMP writes cannot use quarter tags.
      }
  }

  for (reduction <- Seq(false, true)) {
    it should s"retain actual producer data and masks (reduction=$reduction)" in {
      test(new EvuShortProducerHarness(reduction)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.issue.poke(false.B)
        dut.io.read.poke(true.B)
        val source = (0 until 16).foldLeft(BigInt(0)) { (result, lane) =>
          val value = if (reduction) java.lang.Float.floatToRawIntBits(lane.toFloat) else 0x3f800000
          result | (BigInt(value.toLong & 0xffffffffL) << (32 * lane))
        }
        val needed = bytes(if (reduction) 4 else 16)
        val expected = if (reduction) BigInt("41700000", 16) else BigInt("38" * 16, 16)
        dut.io.source.poke(source.U)
        dut.io.needed.poke(needed.U)
        dut.io.issue.poke(true.B)
        dut.clock.step()
        dut.io.issue.poke(false.B)
        for (_ <- 0 until (if (reduction) 3 else 1)) {
          dut.io.write.valid.expect(false.B)
          dut.clock.step()
        }
        dut.io.write.valid.expect(true.B)
        dut.io.write.bits.mask.expect(needed.U)
        dut.io.forwardable.expect(true.B)
        dut.io.bypass.valid.expect(true.B)
        dut.io.bypass.bits.expect(expected.U)
        dut.clock.step()
        dut.io.write.valid.expect(false.B)
        dut.io.bypass.valid.expect(true.B)
        dut.io.bypass.bits.expect(expected.U)
        dut.io.needed.poke((needed | (BigInt(1) << (if (reduction) 32 else 128))).U)
        dut.io.bypass.valid.expect(false.B)
      }
    }
  }

  it should "cover FP8, FP4 and reduction payloads without using missing bytes or older versions" in {
    test(new VectorResultForwarding(2, 1, 1, partial = true)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.tail.poke(28.U)
      dut.io.results.foreach(clear)
      dut.io.writers.foreach(clear)
      dut.io.reads(0).valid.poke(true.B)
      dut.io.reads(0).bits.eg.poke(10.U)
      dut.io.reads(0).bits.vat.poke(2.U)
      for (n <- Seq(16, 8, 4)) {
        dut.io.results(0).valid.poke(true.B)
        dut.io.results(0).bits.eg.poke(10.U)
        dut.io.results(0).bits.vat.poke(30.U)
        dut.io.results(0).bits.data.poke(full.U)
        dut.io.results(0).bits.mask.poke(bytes(n).U)
        dut.io.reads(0).bits.mask.poke(bytes(n).U)
        dut.io.out(0).valid.expect(true.B)
        dut.io.out(0).bits.expect(bytes(n).U)
        dut.io.reads(0).bits.mask.poke(bytes(n + 1).U)
        dut.io.out(0).valid.expect(false.B)
      }
      dut.io.reads(0).bits.mask.poke(bytes(4).U)
      dut.io.results(1).valid.poke(true.B)
      dut.io.results(1).bits.eg.poke(10.U)
      dut.io.results(1).bits.vat.poke(31.U)
      dut.io.results(1).bits.mask.poke((bytes(4) << 32).U)
      dut.io.out(0).valid.expect(false.B) // A newer partial result cannot expose an older version.
      dut.io.results(1).valid.poke(false.B)
      dut.io.writers(0).valid.poke(true.B)
      dut.io.writers(0).bits.vat.poke(31.U)
      dut.io.writers(0).bits.writes.poke((BigInt(1) << 10).U)
      dut.io.out(0).valid.expect(false.B)
      dut.io.newerWriter(0).expect(true.B)
      dut.io.writers(0).valid.poke(false.B)
      dut.io.results(0).bits.vat.poke(2.U)
      dut.io.out(0).valid.expect(false.B)
      dut.io.results(0).bits.vat.poke(3.U)
      dut.io.out(0).valid.expect(false.B)
      dut.io.results(0).bits.vat.poke(30.U)
      dut.io.reads(0).bits.mask.poke(0.U)
      dut.io.out(0).valid.expect(false.B)
    }
  }

  it should "retain accepted results for four cycles and cancel on physical writes, wrap and reset" in {
    test(new VectorResultForwarding(1, 1, 1, partial = true, retainCycles = 4, invalidationPorts = 1))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.tail.poke(5.U)
        dut.io.results.foreach(clear)
        dut.io.writers.foreach(clear)
        dut.io.invalidate.foreach(clear)
        dut.io.accepted.get(0).poke(false.B)
        dut.io.reads(0).valid.poke(true.B)
        dut.io.reads(0).bits.eg.poke(10.U)
        dut.io.reads(0).bits.vat.poke(4.U)
        dut.io.reads(0).bits.mask.poke(bytes(8).U)
        def capture(): Unit = {
          dut.io.results(0).valid.poke(true.B)
          dut.io.results(0).bits.eg.poke(10.U)
          dut.io.results(0).bits.vat.poke(3.U)
          dut.io.results(0).bits.mask.poke(bytes(8).U)
          dut.io.results(0).bits.data.poke(0x1234.U)
          dut.io.accepted.get(0).poke(true.B)
          dut.clock.step()
          dut.io.results(0).valid.poke(false.B)
          dut.io.accepted.get(0).poke(false.B)
        }
        capture()
        dut.io.writers(0).valid.poke(true.B)
        dut.io.writers(0).bits.vat.poke(3.U)
        dut.io.writers(0).bits.writes.poke((BigInt(1) << 10).U)
        for (_ <- 0 until 4) {
          dut.io.out(0).valid.expect(true.B)
          dut.io.out(0).bits.expect(0x1234.U)
          dut.clock.step()
        }
        dut.io.out(0).valid.expect(false.B)
        dut.io.writers(0).valid.poke(false.B)
        capture()
        dut.io.invalidate(0).valid.poke(true.B)
        dut.io.invalidate(0).bits.poke(11.U)
        dut.io.out(0).valid.expect(true.B)
        dut.io.invalidate(0).bits.poke(10.U)
        dut.io.out(0).valid.expect(false.B)
        dut.clock.step()
        dut.io.invalidate(0).valid.poke(false.B)
        dut.io.out(0).valid.expect(false.B)
        capture()
        dut.io.tail.poke(0.U)
        dut.io.out(0).valid.expect(false.B)
        dut.clock.step()
        dut.io.tail.poke(5.U)
        dut.io.out(0).valid.expect(false.B)
        capture()
        dut.reset.poke(true.B)
        dut.io.out(0).valid.expect(false.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.out(0).valid.expect(false.B)
        dut.io.results(0).valid.poke(true.B)
        dut.clock.step()
        dut.io.results(0).valid.poke(false.B)
        dut.io.out(0).valid.expect(false.B) // Unaccepted iterative results are never retained.
      }
  }

  for ((factory, function, f3, length, immediate, needed, operand) <- Seq(
    (FPXX8Factory, 0x3b, 0, 16, 0, bytes(16), 1),
    (FPMXFPFactory, 0x3d, 0, 16, 4, bytes(1), 0),
    (PermuteUnitFactory, OPIFunct6.slideup.litValue.toInt, 3, 16, 14, bytes(8), 1),
    (PermuteUnitFactory, OPIFunct6.slideup.litValue.toInt, 3, 16, 15, bytes(4), 1),
    (PermuteUnitFactory, OPIFunct6.rgather.litValue.toInt, 3, 16, 0, bytes(4), 1),
    (PermuteUnitFactory, OPIFunct6.rgather.litValue.toInt, 3, 16, 3, bytes(4) << 96, 1)
  )) {
    it should s"forward only the actual source bytes for function=$function immediate=$immediate" in {
      test(LazyModule(new EvuShortReadShell(factory, operand)).module)
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          clear(dut.io.command.bits)
          dut.io.command.valid.poke(false.B)
          clear(dut.io.result)
          val sourceEg = if (operand == 0) 8 * immediate else 16
          dut.io.olderWrites.poke((BigInt(1) << sourceEg).U)
          dut.io.olderReads.poke(0.U)
          dut.io.issueReady.poke(false.B)
          val command = dut.io.command.bits
          command.bits.poke(((BigInt(function) << 26) | (BigInt(1) << 25) |
            (2 << 20) | (immediate << 15) | (f3 << 12) | (3 << 7) | 0x57).U)
          command.vconfig.vl.poke(length.U)
          command.vconfig.vtype.vsew.poke(2.U)
          command.vat.poke(2.U)
          command.renv1.poke((operand == 0).B)
          command.scalarVrfSource.poke((operand == 0).B)
          command.renv2.poke(true.B)
          command.wvd.poke(true.B)
          dut.io.command.valid.poke(true.B)
          dut.clock.step()
          dut.io.command.valid.poke(false.B)
          dut.io.masks(operand).expect(needed.U)
          dut.io.result.valid.poke(true.B)
          dut.io.result.bits.eg.poke(sourceEg.U)
          dut.io.result.bits.vat.poke(1.U)
          dut.io.result.bits.data.poke(full.U)
          dut.io.result.bits.mask.poke((needed ^ (needed & -needed)).U)
          dut.io.forwarded(operand).expect(false.B)
          dut.io.issue.expect(false.B)
          dut.io.result.bits.mask.poke(needed.U)
          dut.io.forwarded(operand).expect(true.B)
          dut.io.issue.expect(true.B)
          dut.clock.step() // A stalled consumer captures a covered response.
          dut.io.result.valid.poke(false.B)
          dut.io.olderWrites.poke((BigInt(1) << 24).U)
          dut.io.issue.expect(false.B) // Destination WAW remains in force.
          dut.io.olderReads.poke((BigInt(1) << 24).U)
          dut.io.olderWrites.poke(0.U)
          dut.io.issue.expect(false.B) // Destination WAR remains in force.
          dut.io.olderReads.poke(0.U)
          dut.io.forwarded(operand).expect(false.B)
          val expected = if (operand == 0) (0 until 16).map(i => (needed & bytes(4)) << (32 * i)).reduce(_ | _)
            else needed
          dut.io.data.expect(expected.U)
          dut.io.issueReady.poke(true.B)
          dut.io.issue.expect(true.B)
          dut.clock.step()
          dut.io.issue.expect(false.B)
        }
    }
  }

  it should "capture only the active bytes of a short store and keep them under backpressure" in {
    test(LazyModule(new EvuShortStoreShell).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      clear(dut.io.command.bits)
      clear(dut.io.result)
      dut.io.command.valid.poke(false.B)
      dut.io.ready.poke(false.B)
      dut.io.olderWrites.poke((BigInt(1) << 16).U)
      val command = dut.io.command.bits
      command.bits.poke(((BigInt(1) << 25) | (2 << 12) | (2 << 7) | 0x27).U)
      command.vconfig.vl.poke(3.U)
      command.vconfig.vtype.vsew.poke(2.U)
      command.vstart.poke(1.U)
      command.vat.poke(2.U)
      dut.io.command.valid.poke(true.B)
      dut.clock.step()
      dut.io.command.valid.poke(false.B)
      val needed = bytes(8) << 32
      dut.io.mask.expect(needed.U)
      dut.io.result.valid.poke(true.B)
      dut.io.result.bits.eg.poke(16.U)
      dut.io.result.bits.vat.poke(1.U)
      dut.io.result.bits.data.poke(full.U)
      dut.io.result.bits.mask.poke((needed ^ (BigInt(1) << 32)).U)
      dut.io.forwarded.expect(false.B)
      dut.io.issue.expect(false.B)
      dut.io.result.bits.mask.poke(needed.U)
      dut.io.forwarded.expect(true.B)
      dut.io.storeMask.expect(0xff0.U)
      dut.io.issue.expect(true.B)
      dut.clock.step()
      dut.io.result.valid.poke(false.B)
      dut.io.forwarded.expect(false.B)
      dut.io.data.expect(needed.U)
      dut.io.ready.poke(true.B)
      dut.io.issue.expect(false.B) // Existing RAW checks still wait for physical completion.
      dut.io.olderWrites.poke(0.U)
      dut.io.issue.expect(true.B)
      dut.io.storeMask.expect(0xff0.U)
      dut.clock.step()
      dut.io.issue.expect(false.B)
    }
  }
}
