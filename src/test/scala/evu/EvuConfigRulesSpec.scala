package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuConfigRulesHarness extends Module {
  val ep = EvuParams()
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val avl = Input(UInt(64.W))
    val registerType = Input(UInt(64.W))
    val enable = Input(Bool())
    val forceVill = Input(Bool())
    val result = Output(new EvuExecutionConfig(ep))
  })
  io.result := EvuConfigRules.normalize(ep, io.avl,
    EvuConfigRules.requestedType(io.instruction, io.registerType), io.enable, io.forceVill)
}

class EvuConfigRulesSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU ABI2 shared configuration rules"
  it should "match immediate and scalar VTYPE, clamp full-width AVL and preserve invalid restores" in {
    test(new EvuConfigRulesHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.enable.poke(true.B)
      dut.io.forceVill.poke(false.B)
      for (vtype <- 0 until 256; register <- Seq(false, true)) {
        val instr = if (register) (BigInt(0x46) << 25) | (11 << 20) | (10 << 15) | (7 << 12) | (12 << 7) | 0xb
          else (BigInt(0x45) << 25) | ((vtype & 31) << 20) | (10 << 15) | ((vtype >> 5) << 12) | (12 << 7) | 0xb
        dut.io.instruction.poke(instr.U)
        dut.io.registerType.poke(vtype.U)
        val sew = (vtype >> 3) & 7
        val lmul = vtype & 7
        val illegal = sew > 2 || lmul == 4
        val base = 512 >> sew
        val max = if (illegal) 0 else if ((lmul & 4) != 0) base >> (8 - lmul) else base << lmul
        for (avl <- Seq(BigInt(0), BigInt(1), BigInt(13), BigInt(128), BigInt(1) << 32, (BigInt(1) << 64) - 1)) {
          dut.io.avl.poke(avl.U)
          dut.io.result.vl.expect(avl.min(BigInt(max)).U)
          dut.io.result.vtype.expect((if (illegal) 0 else vtype).U)
          dut.io.result.vill.expect(illegal.B)
          dut.io.result.configured.expect(true.B)
          dut.io.result.valid.expect(true.B)
        }
      }
      dut.io.registerType.poke((BigInt(1) << 63).U)
      dut.io.result.vill.expect(true.B)
      dut.io.result.vl.expect(0.U)
      dut.io.registerType.poke(0x10.U)
      dut.io.forceVill.poke(true.B)
      dut.io.result.vill.expect(true.B)
      dut.io.enable.poke(false.B)
      dut.io.result.configured.expect(false.B)
      dut.io.result.vill.expect(false.B)
      dut.io.result.vtype.expect(0x10.U)
      dut.io.result.vl.expect(128.U)
    }
  }

  it should "advertise ABI2 without descriptor addresses or snapshot padding" in {
    EvuCSRs.snapshotWords shouldBe 13
    EvuCSRs.addresses.exists(a => a >= 0x8d0 && a <= 0x8d7) shouldBe false
    val cap = EvuCSRs.capabilityValue(EvuParams())
    (cap & 255) shouldBe BigInt(2)
    ((cap >> 8) & 255) shouldBe BigInt(0)
    ((cap >> 27) & 1) shouldBe BigInt(1)
    ((cap >> 28) & 1) shouldBe BigInt(1)
    ((cap >> 29) & 1) shouldBe BigInt(1)
    for (bit <- 30 to 32) ((cap >> bit) & 1) shouldBe BigInt(1)
  }
}
