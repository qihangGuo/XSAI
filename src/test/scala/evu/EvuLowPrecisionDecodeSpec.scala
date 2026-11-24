package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuLowPrecisionDecodeSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU low precision dispatch"

  private def instruction(op: Int, sew: Int, dest: Int = 8, source: Int = 4, scale: Int = 0): BigInt =
    (BigInt(op) << 25) | (source << 20) | (scale << 15) | (sew << 12) | (dest << 7) | 0x0b

  it should "route all eighteen custom operations and reject widths, aliases and non-m1 groups" in {
    test(new EvuRvvMathDecodeHarness(true)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.valid.poke(false.B)
      dut.io.ready.poke(false.B)
      dut.io.lmul.poke(0.U)
      val mapping = Seq(
        (0x70,0x38,0,false), (0x71,0x39,0,false), (0x72,0x3a,0,false),
        (0x73,0x3b,0,false), (0x74,0x3c,0,true), (0x75,0x3d,0,true),
        (0x27,0x38,1,false), (0x28,0x38,2,false), (0x29,0x38,3,false),
        (0x2a,0x39,1,false), (0x2b,0x3a,1,false), (0x2c,0x3b,1,false),
        (0x2d,0x3b,2,false), (0x2e,0x3b,3,false), (0x78,0x36,0,true),
        (0x79,0x32,0,true), (0x7a,0x33,0,true), (0x7b,0x34,0,true))
      for ((operation, f6, subop, scaled) <- mapping) {
        val scale = if (scaled) 12 else 0
        dut.io.instruction.poke(instruction(operation, 2, scale = scale).U)
        dut.io.valid.poke(true.B)
        dut.io.legalGroup.expect(true.B)
        dut.clock.step()
        dut.io.valid.poke(false.B)
        for (_ <- 0 until 3) {
          dut.io.decoded.valid.expect(true.B)
          dut.io.decoded.bits.opcode.expect(operation.U)
          dut.io.rvv.expect(((BigInt(f6) << 26) | (1 << 25) |
            (4 << 20) | ((if (scaled) scale else subop) << 15) | (8 << 7) | 0x57).U)
          dut.clock.step()
        }
        dut.io.ready.poke(true.B)
        dut.clock.step()
        dut.io.ready.poke(false.B)
        for (lmul <- 1 to 7) {
          dut.io.lmul.poke(lmul.U)
          dut.io.legalGroup.expect(false.B)
        }
        dut.io.lmul.poke(0.U)
        dut.io.instruction.poke(instruction(operation, 2, dest = 4, scale = scale).U)
        dut.io.legalGroup.expect(false.B)
        if (scale != 0) {
          dut.io.instruction.poke(instruction(operation, 2, dest = scale, scale = scale).U)
          dut.io.legalGroup.expect(false.B)
        }
        for (sew <- Seq(0, 1, 3)) {
          dut.io.instruction.poke(instruction(operation, sew, scale = scale).U)
          dut.io.valid.poke(true.B)
          dut.clock.step()
          dut.io.valid.poke(false.B)
          dut.io.decoded.bits.opcode.expect(EvuOpcode.Invalid)
          dut.io.ready.poke(true.B)
          dut.clock.step()
          dut.io.ready.poke(false.B)
        }
      }
      dut.io.instruction.poke(instruction(0x71, 2, scale = 12).U)
      dut.io.valid.poke(true.B)
      dut.clock.step()
      dut.io.decoded.bits.opcode.expect(EvuOpcode.Invalid)
    }
  }
}
