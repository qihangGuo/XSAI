package evu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuDispatchAdapterSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EvuDispatchAdapter"

  private val ep = EvuParams(enable = true)

  private def initialize(dut: EvuDispatchAdapter): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.out.ready.poke(false.B)
    dut.io.flush.poke(false.B)
  }

  private def issue(dut: EvuDispatchAdapter, funct7: Int, sew: Int, custom: Int = 0x0b): Unit = {
    val instr = (BigInt(funct7) << 25) | (BigInt(31) << 20) |
      (BigInt(17) << 15) | (BigInt(sew) << 12) | (BigInt(23) << 7) | custom
    dut.io.in.bits.instr.poke(instr.U)
    dut.io.in.bits.base.poke("h123456789abcdef0".U)
    dut.io.in.bits.vl.poke(128.U)
    dut.io.in.bits.length.poke(512.U)
    dut.io.in.bits.aux.poke(0.U)
    dut.io.in.bits.stride.poke(-16.S)
    dut.io.in.bits.chain.poke(true.B)
    dut.io.in.bits.pageMap.enabled.poke(true.B)
    dut.io.in.bits.pageMap.base.poke("h40000ffc".U)
    dut.io.in.bits.pageMap.length.poke(512.U)
    for (i <- 0 until ep.translationPages) dut.io.in.bits.pageMap.ppn(i).poke((0x80000 + i * 0x10000).U)
    dut.io.in.valid.poke(true.B)
    dut.io.in.ready.expect(true.B)
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  it should "preserve every field under backpressure and decode the custom0 operations" in {
    test(new EvuDispatchAdapter(ep)) { dut =>
      initialize(dut)
      val operations = Seq(
        1    -> EvuOpcode.Add,
        2    -> EvuOpcode.Sub,
        3    -> EvuOpcode.And,
        4    -> EvuOpcode.Or,
        5    -> EvuOpcode.Xor,
        6    -> EvuOpcode.Mul,
        7    -> EvuOpcode.Mac,
        15   -> EvuOpcode.Exp2,
        0x10 -> EvuOpcode.Load,
        0x11 -> EvuOpcode.Store,
        0x18 -> EvuOpcode.Launch,
        0x19 -> EvuOpcode.Wait,
        0x1a -> EvuOpcode.Fence,
        0x4c -> EvuOpcode.QuantToCute,
        0x4e -> EvuOpcode.SetCfg
      )
      for ((funct7, opcode) <- operations) {
        issue(dut, funct7, sew = 2)
        for (_ <- 0 until 3) {
          dut.io.out.valid.expect(true.B)
          dut.io.out.bits.opcode.expect(opcode)
          dut.io.out.bits.sew.expect(2.U)
          dut.io.out.bits.vd.expect(23.U)
          dut.io.out.bits.vs1.expect(17.U)
          dut.io.out.bits.vs2.expect(31.U)
          dut.io.out.bits.base.expect("h123456789abcdef0".U)
          dut.io.out.bits.vl.expect(128.U)
          dut.io.out.bits.length.expect(512.U)
          dut.io.out.bits.stride.expect(-16.S)
          dut.io.out.bits.chain.expect(true.B)
          dut.io.out.bits.pageMap.enabled.expect(true.B)
          dut.io.out.bits.pageMap.ppn(1).expect("h90000".U)
          dut.io.out.bits.write.expect((funct7 == 0x11).B)
          dut.clock.step()
        }
        dut.io.out.ready.poke(true.B)
        dut.clock.step()
        dut.io.out.ready.poke(false.B)
      }
    }
  }

  it should "reject retired encodings, unsupported widths and unknown operations" in {
    test(new EvuDispatchAdapter(ep)) { dut =>
      initialize(dut)
      val removed = for (funct7 <- Seq(0x08, 0x09, 0x0a, 0x0b, 0x38, 0x49, 0x4a, 0x4b, 0x4d, 0x4f); sew <- 0 to 7) yield (funct7, sew, 0x0b)
      val invalid = Seq((1, 3, 0x0b), (0x10, 3, 0x0b), (0x11, 3, 0x0b),
        (0x30, 3, 0x0b), (0x39, 2, 0x0b), (0x3a, 2, 0x0b), (0x3b, 2, 0x0b), (1, 4, 0x0b), (0x7f, 0, 0x0b), (1, 0, 0x2b)) ++ removed
      for ((funct7, sew, custom) <- invalid) {
        issue(dut, funct7, sew, custom)
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.opcode.expect(EvuOpcode.Invalid)
        dut.io.out.bits.write.expect(false.B)
        dut.io.out.ready.poke(true.B)
        dut.clock.step()
        dut.io.out.ready.poke(false.B)
      }
    }
  }

  it should "accept Exp2 only for IEEE FP16 and FP32" in {
    test(new EvuDispatchAdapter(ep)) { dut =>
      initialize(dut)
      for (sew <- 0 to 7) {
        issue(dut, funct7 = 0x0f, sew = sew)
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.opcode.expect(if (sew == 1 || sew == 2) EvuOpcode.Exp2 else EvuOpcode.Invalid)
        dut.io.out.ready.poke(true.B)
        dut.clock.step()
        dut.io.out.ready.poke(false.B)
      }
    }
  }

  it should "discard a buffered command on flush" in {
    test(new EvuDispatchAdapter(ep)) { dut =>
      initialize(dut)
      issue(dut, funct7 = 1, sew = 0)
      dut.io.out.valid.expect(true.B)
      dut.io.flush.poke(true.B)
      dut.io.out.valid.expect(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.out.valid.expect(false.B)
      dut.io.in.ready.expect(true.B)
    }
  }

  it should "fit the scalar AMU payload and keep the architectural register count fixed" in {
    new EvuCoreCommand(ep).getWidth should be <= 512
    intercept[IllegalArgumentException](EvuParams(vectorRegs = 64))
  }
}
