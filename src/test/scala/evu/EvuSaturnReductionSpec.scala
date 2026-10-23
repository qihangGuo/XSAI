package evu

import chisel3._
import chiseltest._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

import scala.collection.mutable

class EvuSaturnReductionSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Saturn unordered floating-point reductions"

  private def pack(values: Seq[BigInt], width: Int): BigInt =
    values.zipWithIndex.foldLeft(BigInt(0)) { case (bits, (value, index)) => bits | (value << (width * index)) }
  private def single(value: Float): BigInt =
    BigInt(java.lang.Float.floatToRawIntBits(value).toLong & 0xffffffffL)

  it should "match the four-slot reference tree, preserve tails and reproduce the softmax sum" in {
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    val ep = EvuParams(enable = true, enableDifftest = true)
    test(LazyModule(new EvuSaturnExp2Shell(ep)).module)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.clock.setTimeout(100000)
        dut.io.command.valid.poke(false.B)
        dut.io.completion.ready.poke(true.B)
        dut.io.memory.req.ready.poke(true.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        dut.io.flush.poke(false.B)
        dut.io.vstart.poke(0.U)
        val memory = mutable.Map.empty[BigInt, BigInt]
        val responses = mutable.Queue.empty[(BigInt, BigInt)]
        var response = Option.empty[(BigInt, BigInt)]
        var completed = Option.empty[Int]

        def tick(): Unit = {
          if (response.isEmpty && responses.nonEmpty) response = Some(responses.dequeue())
          dut.io.memory.resp.valid.poke(response.nonEmpty.B)
          response.foreach { case (tag, data) =>
            dut.io.memory.resp.bits.tag.poke(tag.U)
            dut.io.memory.resp.bits.data.poke(data.U)
          }
          val taken = response.nonEmpty && dut.io.memory.resp.ready.peek().litToBoolean
          if (dut.io.memory.req.valid.peek().litToBoolean) {
            dut.io.memory.req.bits.write.expect(false.B)
            responses.enqueue((dut.io.memory.req.bits.tag.peek().litValue,
              memory(dut.io.memory.req.bits.addr.peek().litValue)))
          }
          if (dut.io.completion.valid.peek().litToBoolean) {
            dut.io.completion.bits.exception.expect(false.B)
            completed = Some(dut.io.completion.bits.fflags.peek().litValue.toInt)
          }
          dut.clock.step()
          if (taken) response = None
        }
        def execute(opcode: UInt, vd: Int, sew: Int, vl: Int, vs1: Int = 2, vs2: Int = 1,
            flags: Int = 0): Unit = {
          completed = None
          val c = dut.io.command.bits
          c.opcode.poke(opcode)
          c.vd.poke(vd.U)
          c.vs1.poke(vs1.U)
          c.vs2.poke(vs2.U)
          c.sew.poke(sew.U)
          c.vl.poke(vl.U)
          c.base.poke(0x1000.U)
          c.stride.poke(0.S)
          c.length.poke(ep.vlenBytes.U)
          c.aux.poke(0.U)
          c.write.poke(false.B)
          c.chain.poke(false.B)
          c.jobId.poke(0.U)
          c.epoch.poke(0.U)
          c.pageMap.enabled.poke(false.B)
          c.pageMap.base.poke(0.U)
          c.pageMap.length.poke(0.U)
          c.pageMap.ppn.foreach(_.poke(0.U))
          dut.io.command.valid.poke(true.B)
          var waited = 0
          while (!dut.io.command.ready.peek().litToBoolean && waited < 10000) { tick(); waited += 1 }
          dut.io.command.ready.expect(true.B)
          tick()
          dut.io.command.valid.poke(false.B)
          waited = 0
          while (completed.isEmpty && waited < 10000) { tick(); waited += 1 }
          withClue(s"opcode=${opcode.litValue} sew=$sew vl=$vl: ") {
            completed should be(Some(flags))
          }
        }
        def load(reg: Int, values: Seq[BigInt], sew: Int): Unit = {
          val bits = pack(values, 8 << sew)
          val mask = (BigInt(1) << ep.mlenBits) - 1
          for (offset <- 0 until ep.vlenBytes by ep.mlenBytes)
            memory(BigInt(0x1000 + offset)) = (bits >> (offset * 8)) & mask
          execute(EvuOpcode.Load, reg, sew, ep.vlenBytes >> sew)
        }
        def read(reg: Int): BigInt = (0 until ep.vlenBits / ep.dlenBits).foldLeft(BigInt(0)) { (bits, eg) =>
          bits | (dut.io.vrf(reg * (ep.vlenBits / ep.dlenBits) + eg).peek().litValue << (eg * ep.dlenBits))
        }

        for (sew <- Seq(1, 2)) {
          val width = 8 << sew
          val count = ep.vlenBytes >> sew
          val large = if (sew == 1) BigInt(0x6800) else single(16777216f)
          val negative = large.setBit(width - 1)
          val one = if (sew == 1) BigInt(0x3c00) else single(1f)
          val snan = if (sew == 1) BigInt(0x7c01) else BigInt("7f800001", 16)
          val sentinel = if (sew == 1) BigInt(0xa5a5) else BigInt("a5a5a5a5", 16)
          val marker = pack(Seq.fill(count)(sentinel), width)
          load(2, Seq.fill(count)(BigInt(0)), sew)
          val lengths = Seq(0, 1, 3, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129, 255, 256)
          for (vl <- lengths if vl <= count) {
            val source = (0 until count).map { i =>
              if (i == vl) snan
              else if (i == 0) large
              else if (i == 1) one
              else if (i == 2) negative
              else BigInt(0)
            }
            load(1, source, sew)
            load(3, Seq.fill(count)(sentinel), sew)
            execute(EvuOpcode.FReduceSum, 3, sew, vl)
            val expected = if (vl == 0) sentinel else if (vl < 3) large else one
            withClue(s"sew=$sew vl=$vl: ") {
              val actual = read(3)
              (actual & ((BigInt(1) << width) - 1)) should be(expected)
              (actual >> width) should be(marker >> width)
            }
          }
          val lanes = ep.dlenBits / width
          val stream = Seq.fill(count)(BigInt(0)).updated(0, large).updated(lanes, large)
            .updated(2 * lanes, negative).updated(3 * lanes, negative).updated(4 * lanes, one)
          load(1, stream, sew)
          execute(EvuOpcode.FReduceSum, 3, sew, 4 * lanes + 1, flags = 1)
          (read(3) & ((BigInt(1) << width) - 1)) should be(BigInt(0))
        }

        // Log case 1, block 1: a serial FP32 sum yields 0x4200d92b instead.
        val inputs = (0 until 128).map(i => ((i * 37 + 11) % 65 - 32) * 0.0625f + 3f)
        load(1, inputs.map(single), 2)
        load(2, Seq.fill(128)(single(5f)), 2)
        load(29, Seq.fill(128)(BigInt(0)), 2)
        load(30, Seq.fill(128)(single(1.4426950408889634f)), 2)
        execute(EvuOpcode.FSub, 5, 2, 128, vs1 = 2, vs2 = 1)
        execute(EvuOpcode.FMul, 5, 2, 128, vs1 = 30, vs2 = 5, flags = 1)
        execute(EvuOpcode.Exp2, 5, 2, 128, vs2 = 5, flags = 1)
        val exponents = read(5)
        var worstRelative = 0.0
        for (i <- inputs.indices) {
          val actual = java.lang.Float.intBitsToFloat(((exponents >> (32 * i)) & 0xffffffffL).toInt)
          val expected = StrictMath.exp(inputs(i).toDouble - 5.0)
          worstRelative = math.max(worstRelative, math.abs(actual.toDouble - expected) / expected)
        }
        worstRelative should be <= 2e-5
        execute(EvuOpcode.FReduceSum, 6, 2, 128, vs1 = 29, vs2 = 5, flags = 1)
        (read(6) & 0xffffffffL) should be(BigInt("4200d92a", 16))
        println(s"Softmax RTL reproduction: sum=0x4200d92a max_exp_relative=$worstRelative")
      }
  }
}
