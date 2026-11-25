package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common._
import xiangshan.evu.exu._
import xiangshan.evu.insns._

class EvuParallelDivHarness(implicit parameters: Parameters) extends Module {
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val ready = Input(Bool())
    val sqrt = Input(Bool())
    val rounding = Input(UInt(3.W))
    val mask = Input(UInt(64.W))
    val numerator = Input(UInt(512.W))
    val denominator = Input(UInt(512.W))
    val result = Output(UInt(512.W))
    val resultMask = Output(UInt(512.W))
    val resultValid = Output(Bool())
    val flags = Output(UInt(5.W))
    val flagsValid = Output(Bool())
    val busy = Output(Bool())
  })
  val divider = Module(new FPDivSqrt(parallelFp32 = true))
  divider.io.iss.valid := io.valid
  divider.io.iss.op := 0.U.asTypeOf(divider.io.iss.op)
  divider.io.iss.op.funct3 := VectorConsts.OPFVV
  divider.io.iss.op.funct6 := Mux(io.sqrt, OPFFunct6.funary1.asUInt, OPFFunct6.fdiv.asUInt)
  divider.io.iss.op.rvs1_eew := 2.U
  divider.io.iss.op.rvs2_eew := 2.U
  divider.io.iss.op.rvd_eew := 2.U
  divider.io.iss.op.vd_eew := 2.U
  divider.io.iss.op.rm := io.rounding
  divider.io.iss.op.rvs1_data := io.denominator
  divider.io.iss.op.rvs2_data := io.numerator
  divider.io.iss.op.wmask := io.mask
  divider.io.iss.op.tail := true.B
  divider.io.write.ready := io.ready
  divider.io.scalar_write.ready := true.B
  io.result := divider.io.write.bits.data
  io.resultMask := divider.io.write.bits.mask
  io.resultValid := divider.io.write.valid
  io.flags := divider.io.set_fflags.bits
  io.flagsValid := divider.io.set_fflags.valid
  io.busy := divider.io.busy
}

class EvuParallelDivSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val base = Parameters.empty.alterPartial {
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }
  private implicit val parameters: Parameters = EvuVectorParameters(base, 4096, 512, 512,
    VectorParams.lgvParams.copy(parallelFp32Div = true))
  private def word(value: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(value).toLong & 0xffffffffL)
  private def pack(values: Seq[BigInt]): BigInt = values.zipWithIndex.foldLeft(BigInt(0)) {
    case (result, (value, index)) => result | (value << (32 * index))
  }

  behavior of "Parallel FP32 division and square root"

  it should "retain scalar estimates and FP16 while issuing FP32 arithmetic by element group" in {
    val wide = SIMDFPDivSqrtFactory.insns
    wide.count(_.lookup(Elementwise).value == 0) shouldBe 4
    wide.filter(_.lookup(Elementwise).value == 0).foreach(_.lookup(SEW).value shouldBe BigInt(2))
    FPDivSqrtFactory.insns.foreach(_.lookup(Elementwise).value shouldBe BigInt(1))
  }

  it should "join early special results with normal lanes and hold the full result under backpressure" in {
    test(new EvuParallelDivHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val numerator = Seq(word(4), word(0), word(1), word(-1), word(Float.PositiveInfinity), word(1),
        word(1), BigInt(1), word(Float.MaxValue), BigInt("7f800001", 16), word(-0.0f), word(-4),
        word(9), word(16), word(25), word(36))
      val denominator = Seq(2f, 0f, 0f, 0f, Float.PositiveInfinity, Float.PositiveInfinity,
        3f, 2f, 0.5f, 1f, 2f, 2f, 3f, 4f, 5f, 6f).map(word)
      val expected = Seq(word(2), BigInt("7fc00000", 16), word(Float.PositiveInfinity),
        word(Float.NegativeInfinity), BigInt("7fc00000", 16), word(0), word(1f / 3f), word(0),
        word(Float.PositiveInfinity), BigInt("7fc00000", 16), word(-0.0f), word(-2),
        word(3), word(4), word(5), word(6))
      dut.io.valid.poke(false.B)
      dut.io.ready.poke(false.B)
      dut.io.sqrt.poke(false.B)
      dut.io.rounding.poke(0.U)
      dut.io.numerator.poke(pack(numerator).U)
      dut.io.denominator.poke(pack(denominator).U)
      for (active <- Seq(0, 1, 13, 16)) {
        val mask = (BigInt(1) << (4 * active)) - 1
        dut.io.mask.poke(mask.U)
        dut.io.valid.poke(true.B)
        dut.clock.step()
        dut.io.valid.poke(false.B)
        var cycles = 0
        while (!dut.io.resultValid.peek().litToBoolean && cycles < 80) {
          dut.clock.step()
          cycles += 1
        }
        dut.io.resultValid.expect(true.B)
        // Includes the EVU operand and writeback registers. The old one-bit
        // implementation cannot finish a normal FP32 operation in this window.
        cycles should be < 20
        val resultMask = (BigInt(1) << (32 * active)) - 1
        (dut.io.result.peek().litValue & resultMask) shouldBe pack(expected.take(active))
        dut.io.resultMask.expect(resultMask.U)
        dut.io.flags.expect((if (active <= 1) 0 else 31).U)
        val held = dut.io.result.peek().litValue
        for (_ <- 0 until 4) {
          dut.io.result.expect(held.U)
          dut.io.resultValid.expect(true.B)
          dut.io.busy.expect(true.B)
          dut.io.flagsValid.expect(false.B)
          dut.clock.step()
        }
        dut.io.ready.poke(true.B)
        dut.io.flagsValid.expect(true.B)
        dut.clock.step()
        dut.io.ready.poke(false.B)
        dut.io.busy.expect(false.B)
      }
    }
  }

  it should "respect rounding, sparse masks and reset during an outstanding operation" in {
    test(new EvuParallelDivHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.valid.poke(false.B)
      dut.io.ready.poke(false.B)
      dut.io.sqrt.poke(false.B)
      dut.io.mask.poke(BigInt("f00000000000000f", 16).U)
      dut.io.numerator.poke(pack(Seq.fill(16)(word(1))).U)
      dut.io.denominator.poke(pack(Seq.fill(16)(word(3))).U)
      for (rounding <- 0 to 4) {
        dut.io.rounding.poke(rounding.U)
        dut.io.valid.poke(true.B)
        dut.clock.step()
        dut.io.valid.poke(false.B)
        dut.clock.step(19)
        dut.io.resultValid.expect(true.B)
        val rounded = BigInt(if (rounding == 1 || rounding == 2) "3eaaaaaa" else "3eaaaaab", 16)
        dut.io.result.expect((rounded | (rounded << 480)).U)
        dut.io.flags.expect(1.U)
        dut.io.ready.poke(true.B)
        dut.clock.step()
        dut.io.ready.poke(false.B)
      }
      dut.io.mask.poke(((BigInt(1) << 64) - 1).U)
      dut.io.valid.poke(true.B)
      dut.clock.step()
      dut.io.valid.poke(false.B)
      dut.clock.step(4)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(2)
      dut.io.busy.expect(false.B)
      dut.io.resultValid.expect(false.B)
      dut.io.sqrt.poke(true.B)
      dut.io.rounding.poke(0.U)
      dut.io.numerator.poke(pack((1 to 16).map(index => word((index * index).toFloat))).U)
      dut.io.valid.poke(true.B)
      dut.clock.step()
      dut.io.valid.poke(false.B)
      dut.clock.step(19)
      dut.io.resultValid.expect(true.B)
      dut.io.result.expect(pack((1 to 16).map(index => word(index.toFloat))).U)
      dut.io.flags.expect(0.U)
      dut.io.ready.poke(true.B)
      dut.clock.step()
      val roots = Seq(word(2), word(-0.0f), word(Float.PositiveInfinity), word(-1),
        BigInt("7fc00011", 16), BigInt("7f800001", 16), BigInt(2))
      dut.io.mask.poke(((BigInt(1) << (4 * roots.size)) - 1).U)
      dut.io.numerator.poke(pack(roots).U)
      for (rounding <- 0 to 4) {
        dut.io.ready.poke(false.B)
        dut.io.rounding.poke(rounding.U)
        dut.io.valid.poke(true.B)
        dut.clock.step()
        dut.io.valid.poke(false.B)
        dut.clock.step(19)
        val rootTwo = BigInt(if (rounding == 3) "3fb504f4" else "3fb504f3", 16)
        val nan = BigInt("7fc00000", 16)
        dut.io.resultValid.expect(true.B)
        dut.io.result.expect(pack(Seq(rootTwo, word(-0.0f), word(Float.PositiveInfinity),
          nan, nan, nan, BigInt(53) << 23)).U) // sqrt(2^-148) = 2^-74
        dut.io.flags.expect(17.U) // invalid sqrt/sNaN and inexact sqrt(2)
        dut.io.ready.poke(true.B)
        dut.clock.step()
      }
    }
  }
}
