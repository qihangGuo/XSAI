package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuRvvMathDecodeHarness(lowPrecision: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
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
  adapter.io.in.bits.vl := 128.U
  adapter.io.in.valid := io.valid
  adapter.io.out.ready := io.ready
  adapter.io.flush := false.B
  io.decoded.valid := adapter.io.out.valid
  io.decoded.bits := adapter.io.out.bits
  io.rvv := (if (lowPrecision) EvuLowPrecision.encode(adapter.io.out.bits) else EvuRvvMath.encode(adapter.io.out.bits))
  io.legalGroup := EvuIsa.supportsRegisterGroups(io.instruction, io.lmul)
}

class EvuRvvMathDecodeSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU standard RVV math envelopes"

  private def instruction(op: Int, sew: Int, dest: Int = 8, source: Int = 4, source1: Int = 0): BigInt =
    (BigInt(op) << 25) | (source << 20) | (source1 << 15) | (sew << 12) | (dest << 7) | 0x0b

  it should "encode all fifteen forms as ordinary RVV instructions and preserve E0 VL" in {
    test(new EvuRvvMathDecodeHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.valid.poke(false.B)
      dut.io.ready.poke(false.B)
      dut.io.lmul.poke(0.U)
      val cases = Seq(
        (0x60, 2, 0x60, 0, 0x4c001057L, 0), (0x61, 2, 0x61, 0, 0x4c021057L, 0),
        (0x62, 2, 0x60, 64, 0x4c001057L, 0), (0x63, 2, 0x61, 64, 0x4c021057L, 0),
        (0x64, 1, 0x64, 0, 0x4c029057L, 0), (0x65, 2, 0x64, 64, 0x4c029057L, 0),
        (0x66, 2, 0x66, 0, 0x28001057L, 12), (0x67, 1, 0x67, 0, 0x48061057L, 0),
        (0x68, 1, 0x68, 0, 0x480a1057L, 0), (0x69, 2, 0x69, 0, 0x48009057L, 0),
        (0x6a, 2, 0x6a, 0, 0x48019057L, 0), (0x6b, 0, 0x6b, 0, 0xbc003057L, 31),
        (0x6c, 2, 0x6c, 0, 0x48032057L, 0), (0x6d, 1, 0x6d, 0, 0x4803a057L, 0),
        (0x6e, 1, 0x6e, 0, 0xb0003057L, 16))
      for ((op, sew, canonical, aux, encoding, source1) <- cases) {
        dut.io.instruction.poke(instruction(op, sew, source1 = source1).U)
        dut.io.valid.poke(true.B)
        dut.clock.step()
        dut.io.valid.poke(false.B)
        for (_ <- 0 until 3) {
          dut.io.decoded.valid.expect(true.B)
          dut.io.decoded.bits.opcode.expect(canonical.U)
          dut.io.decoded.bits.aux.expect(aux.U)
          dut.io.decoded.bits.vl.expect(128.U)
          dut.io.rvv.expect((BigInt(encoding) | (1 << 25) | (4 << 20) | (source1 << 15) | (8 << 7)).U)
          dut.clock.step()
        }
        dut.io.ready.poke(true.B)
        dut.clock.step()
        dut.io.ready.poke(false.B)
      }
    }
  }

  it should "reject wrong widths, unary source fields, invalid EMUL and forbidden overlaps" in {
    test(new EvuRvvMathDecodeHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.valid.poke(false.B)
      dut.io.ready.poke(false.B)
      dut.io.lmul.poke(0.U)
      val invalid = Seq((0x60, 0, 0), (0x61, 3, 0), (0x62, 1, 0), (0x65, 1, 0),
        (0x67, 2, 0), (0x68, 0, 0), (0x69, 1, 0), (0x6a, 1, 0),
        (0x6b, 2, 0), (0x6c, 0, 0), (0x6d, 0, 0), (0x6e, 2, 0), (0x60, 2, 1))
      for ((op, sew, source1) <- invalid) {
        dut.io.instruction.poke(instruction(op, sew, source1 = source1).U)
        dut.io.valid.poke(true.B)
        dut.clock.step()
        dut.io.valid.poke(false.B)
        dut.io.decoded.bits.opcode.expect(EvuOpcode.Invalid)
        dut.io.ready.poke(true.B)
        dut.clock.step()
        dut.io.ready.poke(false.B)
      }
      val groups = Seq(
        (0x67, 0, 8, 4, true), (0x67, 0, 9, 4, false),
        (0x67, 0, 8, 8, false), (0x67, 0, 8, 9, true),
        (0x68, 0, 4, 4, true), (0x68, 0, 5, 4, false),
        (0x68, 0, 8, 5, false), (0x67, 3, 16, 0, false),
        (0x68, 3, 16, 0, false), (0x6c, 5, 8, 4, false),
        (0x6c, 0, 4, 4, false), (0x6c, 1, 4, 5, true),
        (0x67, 7, 8, 4, true), (0x68, 7, 4, 4, true),
        (0x62, 1, 8, 4, false))
      for ((op, lmul, dest, source, legal) <- groups) {
        dut.io.instruction.poke(instruction(op, 1, dest, source).U)
        dut.io.lmul.poke(lmul.U)
        dut.io.legalGroup.expect(legal.B)
      }
    }
  }
}
