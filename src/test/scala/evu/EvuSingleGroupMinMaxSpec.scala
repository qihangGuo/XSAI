package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.{CoreModule, TileVisibilityNodeKey}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common._

class EvuSingleGroupMinMaxEligibilityHarness(implicit p: Parameters) extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val command = Input(new BackendIssueInst)
    val eligible = Output(Bool())
  })
  io.eligible := VectorFastPaths.singleGroupMinMax(io.command, dLen,
    vParams.fp32MinMaxTree && vParams.singleGroupMinMax)
}

class EvuSingleGroupMinMaxEligibilityShell(implicit p: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("minmax-eligibility-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuSingleGroupMinMaxEligibilityShellImp(this)
}

class EvuSingleGroupMinMaxEligibilityShellImp(outer: EvuSingleGroupMinMaxEligibilityShell)(implicit p: Parameters)
    extends LazyModuleImp(outer) {
  val harness = Module(new EvuSingleGroupMinMaxEligibilityHarness()(p.alterPartial {
    case TileVisibilityNodeKey => outer.client
  }))
  val io = IO(chiselTypeOf(harness.io))
  io <> harness.io
}

class EvuSingleGroupMinMaxSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val base = Parameters.empty.alterPartial {
    case MonitorsEnabled => false
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }
  private def clear(data: Data): Unit = data match {
    case value: Bool => value.poke(false.B)
    case value: UInt => value.poke(0.U)
    case value: SInt => value.poke(0.S)
    case values: Vec[_] => values.foreach(clear)
    case bundle: Bundle => bundle.elements.values.foreach(clear)
  }
  private def pack(values: Seq[Long]): BigInt = values.zipWithIndex.foldLeft(BigInt(0)) {
    case (result, (value, index)) => result | (BigInt(value & 0xffffffffL) << (32 * index))
  }
  private def reference(values: Seq[Long], minimum: Boolean): (Long, Int) = {
    val invalid = values.exists(v => (v & 0x7f800000L) == 0x7f800000L &&
      (v & 0x7fffffL) != 0 && (v & 0x400000L) == 0)
    val numeric = values.filterNot(v => java.lang.Float.isNaN(java.lang.Float.intBitsToFloat(v.toInt)))
    val result = if (numeric.isEmpty) 0x7fc00000L else numeric.reduce { (a, b) =>
      val first = java.lang.Float.intBitsToFloat(a.toInt)
      val second = java.lang.Float.intBitsToFloat(b.toInt)
      if (first == 0 && second == 0) {
        if (minimum) (a | b) & 0x80000000L else (a & b) & 0x80000000L
      } else if (if (minimum) first < second else first > second) a else b
    }
    (result, if (invalid) 16 else 0)
  }
  private def command(op: BackendIssueInst, context: Int, length: Int, masked: Boolean,
      alias: Int = 0): Unit = {
    clear(op)
    val function = if (context == 0) OPFFunct6.fredmin else OPFFunct6.fredmax
    val source = context + 1
    val seed = context + 5
    val destination = if (alias == 1) source else if (alias == 2) seed else context + 3
    op.bits.poke(((function.litValue << 26) | (if (masked) BigInt(0) else BigInt(1) << 25) |
      (source << 20) | (seed << 15) | (1 << 12) | (destination << 7) | 0x57).U)
    op.vconfig.vl.poke(length.U)
    op.vconfig.vtype.vsew.poke(2.U)
    op.vat.poke((context + 1).U)
    op.reduction.poke(true.B)
    op.renv1.poke(true.B)
    op.renv2.poke(true.B)
    op.renvm.poke(masked.B)
    op.wvd.poke(true.B)
  }

  behavior of "Single-EG direct FP32 min/max"

  it should "select only full unmasked FP32 groups with both feature switches enabled" in {
    for (datapath <- Seq(64, 512); (tree, direct) <- Seq((true, true), (true, false), (false, true))) {
      implicit val p: Parameters = EvuVectorParameters(base, 4096, datapath, datapath,
        VectorParams.lgvParams.copy(fp32MinMaxTree = tree, singleGroupMinMax = direct))
      test(LazyModule(new EvuSingleGroupMinMaxEligibilityShell).module)
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        for (context <- 0 until 2) {
          command(dut.io.command, context, datapath / 32, masked = false)
          dut.io.eligible.expect((tree && direct).B)
        }
        val mutations: Seq[() => Unit] = Seq(
          () => dut.io.command.vstart.poke(1.U),
          () => dut.io.command.vconfig.vl.poke((datapath / 32 - 1).U),
          () => dut.io.command.vconfig.vl.poke((datapath / 32 + 1).U),
          () => dut.io.command.vconfig.vtype.vsew.poke(1.U),
          () => dut.io.command.wide_vd.poke(true.B),
          () => dut.io.command.wide_vs2.poke(true.B),
          () => dut.io.command.renvm.poke(true.B),
          () => dut.io.command.reduction.poke(false.B),
          () => command(dut.io.command, 0, datapath / 32, masked = true),
          () => dut.io.command.bits.poke(((OPFFunct6.fredusum.litValue << 26) |
            (BigInt(1) << 25) | (1 << 12) | 0x57).U))
        for (mutate <- mutations) {
          command(dut.io.command, 0, datapath / 32, masked = false)
          mutate()
          dut.io.eligible.expect(false.B)
        }
      }
    }
  }

  for (direct <- Seq(false, true)) {
    it should s"preserve seeds, exceptional values and fallback semantics (direct=$direct)" in {
      implicit val p: Parameters = EvuVectorParameters(base, 4096, 512, 512,
        VectorParams.lgvParams.copy(fp32MinMaxTree = true, singleGroupMinMax = direct,
          reductionContexts = 2, interleaveReductions = true))
      test(LazyModule(new EvuSharedReductionShell(maximum = true, operandInputs = true)).module)
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          val finite = Seq.tabulate(16)(i => java.lang.Float.floatToRawIntBits((i - 8).toFloat).toLong & 0xffffffffL)
          val qnan = 0x7fc00001L
          val snan = 0x7f800001L
          val random = new scala.util.Random(17)
          val patterns = Seq(
            (finite, 0x41200000L), (finite, 0xc1200000L),
            (Seq.fill(16)(qnan), qnan), (Seq.fill(16)(snan), qnan),
            (Seq.fill(16)(qnan), 0x3f800000L), (finite, snan),
            (Seq.fill(16)(0x80000000L), 0L), (Seq.fill(16)(0L), 0x80000000L),
            (Seq.tabulate(16)(i => Seq(1L, 0x80000001L, 0x7f800000L, 0xff800000L)(i % 4)), qnan)) ++
            Seq.fill(12)((Seq.fill(16)(random.nextInt().toLong & 0xffffffffL),
              random.nextInt().toLong & 0xffffffffL))
          val cases = patterns.map { case (values, seed) =>
            (values, seed, 16, false, BigInt(0xffff))
          } ++ Seq(
            (finite.updated(15, snan), qnan, 13, false, BigInt(0xffff)),
            (finite, qnan, 1, false, BigInt(0xffff)),
            (finite, qnan, 17, false, BigInt(0x1ffff)),
            (finite, qnan, 128, false, (BigInt(1) << 128) - 1),
            (finite.updated(15, snan), qnan, 16, true, BigInt(0x7fff)),
            (finite, snan, 16, true, BigInt(0)))
          for (((values, seed, length, masked, mask), index) <- cases.zipWithIndex) {
            dut.io.command.foreach(_.valid.poke(false.B))
            dut.reset.poke(true.B)
            dut.clock.step(2)
            dut.reset.poke(false.B)
            for (context <- 0 until 2) {
              command(dut.io.command(context).bits, context, length, masked, alias = index % 3)
              dut.io.source(context).poke(pack(values).U)
              dut.io.seed(context).poke(BigInt(seed).U)
              dut.io.mask(context).poke(mask.U)
              dut.io.sourceReady(context).poke(true.B)
              dut.io.command(context).valid.poke(true.B)
              dut.io.command(context).ready.expect(true.B)
            }
            dut.clock.step()
            dut.io.command.foreach(_.valid.poke(false.B))
            var cycles = 0
            while (!dut.io.completed.forall(_.peek().litToBoolean) && cycles < 150) {
              dut.io.sourceReady(0).poke((cycles % 7 != 0).B)
              dut.io.sourceReady(1).poke((cycles % 5 != 0).B)
              dut.clock.step()
              cycles += 1
            }
            val active = (0 until length).filter(i => !masked || mask.testBit(i)).map(i => values(i % 16))
            val expected = (0 until 2).map(context =>
              if (active.isEmpty) (seed, 0) else reference(seed +: active, context == 0))
            for (context <- 0 until 2) {
              dut.io.completed(context).expect(true.B)
              dut.io.ownerBusy(context).expect(false.B)
              dut.io.result(context).expect(BigInt(expected(context)._1).U)
              val selected = direct && !masked && length == 16
              dut.io.directs(context).expect((if (selected) 1 else 0).U)
              if (selected) {
                dut.io.issues(context).expect(1.U)
                dut.io.returns(context).expect(0.U)
                dut.io.folds(context).expect(0.U)
                dut.io.merges(context).expect(0.U)
              } else dut.io.returns(context).peek().litValue should be > BigInt(0)
            }
            dut.io.flags.expect(expected.map(_._2).reduce(_ | _).U)
          }
        }
    }
  }

  it should "retain operands under RAW/WAR/WAW and seed/write/issue backpressure, then reuse owners" in {
    implicit val p: Parameters = EvuVectorParameters(base, 4096, 512, 512,
      VectorParams.lgvParams.copy(fp32MinMaxTree = true, singleGroupMinMax = true,
        reductionContexts = 2, interleaveReductions = true))
    test(LazyModule(new EvuSharedReductionShell(maximum = true, operandInputs = true, controlled = true)).module)
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.issueReady.poke(false.B)
        dut.io.writeReady.poke(false.B)
        for (context <- 0 until 2) {
          command(dut.io.command(context).bits, context, 16, masked = false)
          dut.io.source(context).poke(pack(Seq.fill(16)(0x3f800000L)).U)
          dut.io.seed(context).poke(0x40000000L.U)
          dut.io.sourceReady(context).poke(true.B)
          dut.io.seedReady(context).poke(false.B)
          dut.io.olderWrites(context).poke((BigInt(1) << ((context + 1) * 8)).U)
          dut.io.olderReads(context).poke(0.U)
          dut.io.command(context).valid.poke(true.B)
        }
        dut.clock.step()
        dut.io.command.foreach(_.valid.poke(false.B))
        dut.clock.step(3)
        dut.io.issues.foreach(_.expect(0.U))
        dut.io.olderWrites.foreach(_.poke(0.U))
        dut.clock.step()
        // The source has been captured; changing the fixture must not change this instruction.
        dut.io.source.foreach(_.poke(0.U))
        dut.clock.step(2)
        dut.io.issues.foreach(_.expect(0.U))
        dut.io.seedReady.foreach(_.poke(true.B))
        dut.clock.step(2)
        dut.io.issueReady.poke(true.B)
        dut.clock.step(2)
        dut.io.issues.foreach(_.expect(0.U))
        for (context <- 0 until 2) {
          dut.io.olderWrites(context).poke((BigInt(1) << ((context + 3) * 8)).U)
        }
        dut.io.writeReady.poke(true.B)
        dut.clock.step(2)
        dut.io.issues.foreach(_.expect(0.U))
        dut.io.olderWrites.foreach(_.poke(0.U))
        for (context <- 0 until 2) {
          dut.io.olderReads(context).poke((BigInt(1) << ((context + 3) * 8)).U)
        }
        dut.clock.step(2)
        dut.io.issues.foreach(_.expect(0.U))
        dut.io.olderReads.foreach(_.poke(0.U))
        dut.io.issueReady.poke(false.B)
        dut.clock.step(2)
        dut.io.issues.foreach(_.expect(0.U))
        dut.io.issueReady.poke(true.B)
        dut.clock.step(12)
        dut.io.result(0).expect(0x3f800000L.U)
        dut.io.result(1).expect(0x40000000L.U)
        dut.io.issues.foreach(_.expect(1.U))
        dut.io.returns.foreach(_.expect(0.U))
        dut.io.ownerBusy.foreach(_.expect(false.B))
        // Reuse both owners without reset, with a different seed and source.
        for (context <- 0 until 2) {
          command(dut.io.command(context).bits, context, 16, masked = false)
          dut.io.source(context).poke(pack(Seq.fill(16)(0x40400000L)).U)
          dut.io.seed(context).poke(0x40800000L.U)
          dut.io.command(context).valid.poke(true.B)
          dut.io.command(context).ready.expect(true.B)
        }
        dut.clock.step()
        dut.io.command.foreach(_.valid.poke(false.B))
        dut.clock.step(12)
        dut.io.result(0).expect(0x40400000L.U)
        dut.io.result(1).expect(0x40800000L.U)
        dut.io.issues.foreach(_.expect(2.U))
        dut.io.returns.foreach(_.expect(0.U))
        dut.io.ownerBusy.foreach(_.expect(false.B))
        dut.io.flags.expect(0.U)
      }
  }
}
