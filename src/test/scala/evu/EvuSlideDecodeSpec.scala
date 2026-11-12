package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuSlideDecodeHarness extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val offset = Input(UInt(64.W))
    val lmul = Input(UInt(3.W))
    val valid = Input(Bool())
    val ready = Input(Bool())
    val decoded = Output(Valid(new EvuCommand(EvuParams())))
    val rvv = Output(UInt(32.W))
    val legalGroup = Output(Bool())
  })
  val adapter = Module(new EvuDispatchAdapter(EvuParams(enable = true)))
  adapter.io.in.bits := 0.U.asTypeOf(adapter.io.in.bits)
  adapter.io.in.bits.instr := io.instruction
  adapter.io.in.bits.base := io.offset
  adapter.io.in.bits.vl := 13.U
  adapter.io.in.valid := io.valid
  adapter.io.out.ready := io.ready
  adapter.io.flush := false.B
  io.decoded.valid := adapter.io.out.valid
  io.decoded.bits := adapter.io.out.bits
  io.rvv := EvuSaturnSlide.encode(adapter.io.out.bits)
  io.legalGroup := EvuIsa.supportsRegisterGroups(io.instruction, io.lmul)
}

class EvuSlideDecodeSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU scalar-offset slides"

  private def instruction(op: Int, sew: Int, dest: Int = 8, source: Int = 4, scalar: Int = 31): BigInt =
    (BigInt(op) << 25) | (source << 20) | (scalar << 15) | (sew << 12) | (dest << 7) | 0x0b

  it should "preserve unsigned scalar offsets and emit VX rather than VV under backpressure" in {
    test(new EvuSlideDecodeHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.valid.poke(false.B)
      dut.io.ready.poke(false.B)
      dut.io.lmul.poke(1.U)
      for (op <- Seq(0x3e, 0x3f); sew <- 0 to 2; offset <- Seq(BigInt(0), BigInt(17), BigInt(1) << 40, (BigInt(1) << 64) - 1)) {
        dut.io.instruction.poke(instruction(op, sew).U)
        dut.io.offset.poke(offset.U)
        dut.io.legalGroup.expect(true.B)
        dut.io.valid.poke(true.B)
        dut.clock.step()
        dut.io.valid.poke(false.B)
        val rvv = (BigInt(if (op == 0x3e) 0x0e else 0x0f) << 26) | (1 << 25) |
          (4 << 20) | (31 << 15) | (4 << 12) | (8 << 7) | 0x57
        for (_ <- 0 until 3) {
          dut.io.decoded.valid.expect(true.B)
          dut.io.decoded.bits.opcode.expect(op.U)
          dut.io.decoded.bits.base.expect(offset.U)
          dut.io.decoded.bits.vl.expect(13.U)
          dut.io.rvv.expect(rvv.U)
          dut.clock.step()
        }
        dut.io.ready.poke(true.B)
        dut.clock.step()
        dut.io.ready.poke(false.B)
      }
      val groups = Seq((0, 8, 4, true, true), (0, 4, 4, false, true),
        (1, 8, 4, true, true), (1, 4, 4, false, true), (1, 5, 4, false, false),
        (1, 8, 5, false, false), (1, 31, 4, false, false), (1, 8, 31, false, false),
        (7, 8, 4, true, true), (7, 4, 4, false, true), (4, 8, 4, false, false))
      for ((lmul, dest, source, upLegal, downLegal) <- groups; op <- Seq(0x3e, 0x3f)) {
        dut.io.instruction.poke(instruction(op, 2, dest, source).U)
        dut.io.lmul.poke(lmul.U)
        dut.io.legalGroup.expect((if (op == 0x3e) upLegal else downLegal).B)
      }
    }
  }
}
