package evu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuQuantSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EvuQuant"

  it should "convert INT32 to FP32 and apply scaleA times scaleB" in {
    val ep = EvuParams(enable = true, vlenBits = 512)
    test(new EvuQuant(ep)) { dut =>
      def packInt(values: Seq[Int]): BigInt = values.zipWithIndex.foldLeft(BigInt(0)) {
        case (sum, (value, index)) => sum | ((BigInt(value) & 0xffffffffL) << (index * 32))
      }
      def bits(value: Float): Int = java.lang.Float.floatToRawIntBits(value)
      val input = 1 to 8
      val scale = Seq.fill(8)(bits(0.5f))
      val bias = Seq.fill(8)(bits(2.0f))
      val expected = input.map(value => bits(value.toFloat))

      dut.io.in.bits.poke(packInt(input).U)
      dut.io.scale.poke(packInt(scale).U)
      dut.io.bias.poke(packInt(bias).U)
      dut.io.zero.poke(0.S)
      dut.io.shift.poke(0.U)
      dut.io.sew.poke(2.U)
      dut.io.op.poke(EvuQuantOp.Dequant)
      dut.io.floating.poke(true.B)
      dut.io.scaleProduct.poke(true.B)
      dut.io.foldLog2e.poke(false.B)
      dut.io.broadcastIndex.poke(0.U)
      dut.io.validLanes.poke(255.U)
      dut.io.out.ready.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.expect(packInt(expected).U)
      dut.io.fflags.expect(0.U)
    }
  }
}
