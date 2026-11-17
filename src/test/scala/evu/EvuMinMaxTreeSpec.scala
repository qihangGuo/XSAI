package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common._
import xiangshan.evu.exu._

class EvuMinMaxPipeHarness(implicit parameters: Parameters) extends Module {
  val unit = Module(new ExecutionUnit(Seq(FPCmpFactory), "minmax_tree_test"))
  val io = IO(new Bundle {
    val issue = Flipped(Decoupled(new ExecuteMicroOpWithData(1)))
    val write = Output(Valid(new VectorWrite(parameters(VectorParamsKey).dLen)))
    val flags = Output(Valid(new VectorFPFlags))
    val completed = Output(Valid(UInt(parameters(VectorParamsKey).vatSz.W)))
  })
  unit.io.iss <> io.issue
  unit.io.iter_write.ready := true.B
  unit.io.scalar_write.ready := true.B
  unit.io.shared_fp_req.ready := true.B
  unit.io.shared_fp_resp := 0.U.asTypeOf(unit.io.shared_fp_resp)
  io.write := unit.io.pipe_write
  io.flags := unit.io.tagged_fflags(0)
  io.completed := unit.io.completed
}

class EvuMinMaxTreeSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val base = Parameters.empty.alterPartial {
    case MonitorsEnabled => false
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }
  private def pack(values: Seq[Long]): BigInt = values.zipWithIndex.foldLeft(BigInt(0)) {
    case (result, (value, index)) => result | (BigInt(value & 0xffffffffL) << (32 * index))
  }
  private def clear(data: Data): Unit = data match {
    case value: Bool => value.poke(false.B)
    case value: UInt => value.poke(0.U)
    case value: SInt => value.poke(0.S)
    case values: Vec[_] => values.foreach(clear)
    case bundle: Bundle => bundle.elements.values.foreach(clear)
  }
  private def reference(values: Seq[Long], minimum: Boolean): (Long, Int) = {
    val invalid = values.exists(value => (value & 0x7f800000L) == 0x7f800000L &&
      (value & 0x7fffffL) != 0 && (value & 0x400000L) == 0)
    val numeric = values.filterNot(value => java.lang.Float.isNaN(java.lang.Float.intBitsToFloat(value.toInt)))
    val result = if (numeric.isEmpty) 0x7fc00000L else numeric.reduce { (first, second) =>
      val firstValue = java.lang.Float.intBitsToFloat(first.toInt)
      val secondValue = java.lang.Float.intBitsToFloat(second.toInt)
      if (firstValue == 0 && secondValue == 0) {
        if (minimum) (first | second) & 0x80000000L else (first & second) & 0x80000000L
      } else if (if (minimum) firstValue < secondValue else firstValue > secondValue) first else second
    }
    (result, if (invalid) 16 else 0)
  }

  behavior of "Pipelined FP32 minimum/maximum reduction"

  for (datapath <- Seq(64, 128, 256, 512)) {
    it should s"accept consecutive groups and preserve NaNs, signed zero and bubbles at DLEN=$datapath" in {
      test(new FPMinMaxTree(datapath)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        val count = datapath / 32
        val depth = VectorFastPaths.minMaxTreeDepth(datapath)
        val random = new scala.util.Random(datapath)
        val patterns = Seq(Seq.fill(count)(0x7fc00001L), Seq.fill(count)(0x7f800001L),
          Seq.tabulate(count)(index => if (index % 2 == 0) 0L else 0x80000000L),
          Seq.tabulate(count)(index => Seq(0x7f800000L, 0xff800000L, 1L, 0x80000001L)(index % 4))) ++
          Seq.fill(40)(Seq.fill(count)(random.nextInt().toLong & 0xffffffffL))
        val pending = scala.collection.mutable.Map.empty[Int, (Long, Int)]
        val transactions = for (values <- patterns; minimum <- Seq(false, true)) yield (values, minimum)
        val total = transactions.size + transactions.size / 3 + depth + 4
        var next = 0
        for (cycle <- 0 until total) {
          pending.remove(cycle).foreach { case (result, flags) =>
            dut.io.result.expect(BigInt(result).U)
            dut.io.flags.expect(flags.U)
          }
          val issue = next < transactions.size && cycle % 4 != 3
          dut.io.valid.poke(issue.B)
          if (issue) {
            val (values, minimum) = transactions(next)
            dut.io.source.poke(pack(values).U)
            dut.io.minimum.poke(minimum.B)
            pending(cycle + depth) = reference(values, minimum)
            next += 1
          }
          dut.clock.step()
        }
        next shouldBe transactions.size
        pending shouldBe empty
      }
    }
  }

  it should "share two/four/five-cycle results and tag flags with the completing VAT" in {
    implicit val parameters: Parameters = EvuVectorParameters(base, 4096, 512, 512,
      VectorParams.lgvParams.copy(fp32MinMaxTree = true, singleGroupMinMax = true))
    test(new EvuMinMaxPipeHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val pending = scala.collection.mutable.Map.empty[Int, (BigInt, BigInt, Int, Int)]
      val random = new scala.util.Random(32)
      var issued = 0
      for (cycle <- 0 until 150) {
        dut.io.write.valid.expect(pending.contains(cycle).B)
        pending.remove(cycle).foreach { case (result, mask, flags, vat) =>
          (dut.io.write.bits.data.peek().litValue & mask) shouldBe result
          dut.io.write.bits.mask.expect(mask.U)
          dut.io.flags.valid.expect(true.B)
          dut.io.flags.bits.flags.expect(flags.U)
          dut.io.flags.bits.vat.expect(vat.U)
          dut.io.completed.valid.expect(true.B)
          dut.io.completed.bits.expect(vat.U)
        }
        clear(dut.io.issue.bits)
        val tree = issued % 3 != 1
        val direct = issued % 3 == 2
        val depth = if (direct) 5 else if (tree) 4 else 2
        val issue = issued < 60 && !pending.contains(cycle + depth)
        dut.io.issue.valid.poke(issue.B)
        if (issue) {
          val op = dut.io.issue.bits
          val vat = issued % 16
          op.fu_sel.poke(1.U)
          op.funct3.poke(VectorConsts.OPFVV)
          op.rvs1_eew.poke(2.U)
          op.rvs2_eew.poke(2.U)
          op.rvd_eew.poke(2.U)
          op.vd_eew.poke(2.U)
          op.sew.poke(2.U)
          op.vm.poke(true.B)
          op.tail.poke(true.B)
          op.vat.poke(vat.U)
          op.pipe_depth.poke((depth - 1).U)
          op.wmask.poke((if (tree) BigInt(15) else (BigInt(1) << 64) - 1).U)
          if (tree) {
            val values = Seq.fill(16)(random.nextInt().toLong & 0xffffffffL)
              .updated(issued % 16, if (issued % 2 == 0) 0x7f800001L else 0x7fc00001L)
            val minimum = issued % 2 == 0
            val seed = Seq(0L, 0x7f800001L, 0x7fc00001L, 0x7f800000L, 0xff800000L)(issued % 5)
            val (result, flags) = reference((if (direct) Seq(seed) else Seq.empty) ++ values, minimum)
            op.funct6.poke((if (minimum) OPFFunct6.fredmin.litValue else OPFFunct6.fredmax.litValue).U)
            op.acc.poke(true.B)
            op.acc_fold.poke((!direct).B)
            op.acc_tree.poke((!direct).B)
            op.acc_direct.poke(direct.B)
            op.rvs1_data.poke(BigInt(seed).U)
            op.rvs2_data.poke(pack(values).U)
            pending(cycle + depth) = (BigInt(result), BigInt("ffffffff", 16), flags, vat)
          } else {
            op.funct6.poke(OPFFunct6.fsgnjx.litValue.U)
            val values = Seq.fill(16)(random.nextInt().toLong & 0xffffffffL)
            op.rvs1_data.poke(pack(values).U)
            op.rvs2_data.poke(pack(values).U)
            pending(cycle + depth) = (pack(values.map(_ & 0x7fffffffL)), (BigInt(1) << 512) - 1, 0, vat)
          }
          dut.io.issue.ready.expect(true.B)
          issued += 1
        }
        dut.clock.step()
      }
      issued shouldBe 60
      pending shouldBe empty
    }
  }

  it should "retain seeds, tails and masks through production reduction contexts with and without the tree" in {
    for (tree <- Seq(false, true)) {
      implicit val parameters: Parameters = EvuVectorParameters(base, 4096, 512, 512,
        VectorParams.lgvParams.copy(fp32MinMaxTree = tree, reductionContexts = 2, interleaveReductions = true))
      test(LazyModule(new EvuSharedReductionShell(maximum = true, operandInputs = true)).module)
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          val qnan = 0x7fc00001L
          val snan = 0x7f800001L
          val finite = Seq.tabulate(16)(index => java.lang.Float.floatToRawIntBits((index - 8).toFloat).toLong & 0xffffffffL)
          val cases = Seq(
            (finite, 0L, BigInt(0xffff), 16),
            (Seq.fill(16)(qnan), qnan, BigInt(0xffff), 16),
            (Seq.fill(16)(snan), 0x3f800000L, BigInt(0), 16),
            (finite.updated(15, snan), qnan, BigInt(0x7fff), 16),
            (finite.updated(15, snan), qnan, BigInt(0xffff), 13),
            (finite.updated(0, snan), qnan, BigInt(0xffff), 16),
            (Seq.tabulate(16)(index => if (index % 2 == 0) 0L else 0x80000000L), qnan, BigInt(0xffff), 16),
            (finite, snan, BigInt(0xffff), 16),
            (finite, snan, BigInt(0), 16),
            (finite, qnan, BigInt(0), 16),
            (finite, snan, BigInt(0), 128)) ++
            Seq(1, 17, 32, 33, 64, 128).map(length =>
              (finite, qnan, (BigInt(1) << length) - 1, length))
          for ((values, seed, mask, length) <- cases) {
            dut.io.command.foreach(_.valid.poke(false.B))
            dut.reset.poke(true.B)
            dut.clock.step(2)
            dut.reset.poke(false.B)
            for (context <- 0 until 2) {
              val command = dut.io.command(context)
              clear(command.bits)
              val function = if (context == 0) OPFFunct6.fredmin else OPFFunct6.fredmax
              command.bits.bits.poke(((function.litValue << 26) | ((context + 1) << 20) |
                (1 << 12) | ((context + 3) << 7) | 0x57).U)
              command.bits.vconfig.vl.poke(length.U)
              command.bits.vconfig.vtype.vsew.poke(2.U)
              command.bits.vat.poke((context + 1).U)
              command.bits.reduction.poke(true.B)
              command.bits.renv1.poke(true.B)
              command.bits.renv2.poke(true.B)
              command.bits.renvm.poke(true.B)
              command.bits.wvd.poke(true.B)
              dut.io.source(context).poke(pack(values).U)
              dut.io.seed(context).poke(BigInt(seed).U)
              dut.io.mask(context).poke(mask.U)
              dut.io.sourceReady(context).poke(true.B)
              command.valid.poke(true.B)
              command.ready.expect(true.B)
            }
            dut.clock.step()
            dut.io.command.foreach(_.valid.poke(false.B))
            var cycle = 0
            while (!dut.io.completed.forall(_.peek().litToBoolean) && cycle < 220) {
              dut.io.sourceReady(0).poke((cycle % 7 != 0).B)
              dut.io.sourceReady(1).poke((cycle % 5 != 0).B)
              dut.clock.step()
              cycle += 1
            }
            val active = (0 until length).filter(mask.testBit).map(index => values(index % 16))
            val expected = (0 until 2).map(context =>
              if (active.isEmpty) (seed, 0) else reference(seed +: active, context == 0))
            dut.io.completed.foreach(_.expect(true.B))
            for (context <- 0 until 2) {
              dut.io.result(context).expect(BigInt(expected(context)._1).U)
              dut.io.folds(context).expect((if (tree || active.isEmpty) 1 else 4).U)
            }
            dut.io.flags.expect(expected.map(_._2).reduce(_ | _).U)
          }
        }
    }
  }
}
