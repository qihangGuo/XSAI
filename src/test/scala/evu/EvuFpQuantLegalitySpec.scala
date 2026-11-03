package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu._

class EvuFpQuantLegalityHarness(p: EvuParams) extends Module {
  val io = IO(new Bundle {
    val instr = Input(UInt(32.W))
    val base = Input(UInt(64.W))
    val vl = Input(UInt(13.W))
    val lmul = Input(UInt(3.W))
    val targetSupported = Output(Bool())
    val groupsSupported = Output(Bool())
    val widthSupported = Output(Bool())
  })
  io.targetSupported := EvuIsa.supportsFpQuantTarget(p, io.instr, io.base, io.vl)
  io.groupsSupported := EvuIsa.supportsRegisterGroups(io.instr, io.lmul)
  io.widthSupported := EvuIsa.supportsElementWidth(io.instr(31, 25).pad(8), io.instr(14, 12))
}

class EvuFpQuantLegalitySpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "CPU-side FP quantization legality"

  private def params(dlen: Int): EvuParams = EvuParams(enable = true,
    dlenBits = dlen, mlenBits = dlen min 512, lanes = dlen / 64,
    vrfBanks = if (dlen == 512) 4 else 8)

  private val scalarQuant = BigInt("bc07a60b", 16)
  private val vectorQuant = BigInt("a007a60b", 16)

  for (dlen <- Seq(256, 512)) {
    it should s"accept both quant forms and retain target, VL, SEW and LMUL checks at DLEN=$dlen" in {
      test(new EvuFpQuantLegalityHarness(params(dlen))).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.base.poke(1.U)
        dut.io.vl.poke(64.U)
        dut.io.lmul.poke(0.U)
        // Includes the exact first quant instruction rejected by the server.
        for (instr <- Seq(scalarQuant, vectorQuant)) {
          dut.io.instr.poke(instr.U)
          dut.io.groupsSupported.expect(true.B)
          dut.io.widthSupported.expect(true.B)
          for (target <- Seq(0, 1, 511); vl <- Seq(0, 1, 15, 16, 17, 32, 64)) {
            dut.io.base.poke(target.U)
            dut.io.vl.poke(vl.U)
            dut.io.targetSupported.expect(true.B)
          }
          dut.io.vl.poke(64.U)
          for (target <- Seq(BigInt(512), BigInt(1) << 32, (BigInt(1) << 64) - 1)) {
            dut.io.base.poke(target.U)
            dut.io.targetSupported.expect(false.B)
          }
          dut.io.base.poke(1.U)
          for (vl <- Seq(65, 128, 8191)) {
            dut.io.vl.poke(vl.U)
            dut.io.targetSupported.expect(false.B)
          }
          dut.io.vl.poke(64.U)
          for (lmul <- Seq(1, 2, 3)) {
            dut.io.lmul.poke(lmul.U)
            dut.io.groupsSupported.expect(false.B)
          }
          dut.io.lmul.poke(0.U)
          for (sew <- Seq(0, 1, 3)) {
            dut.io.instr.poke(((instr & ~BigInt(0x7000)) | (BigInt(sew) << 12)).U)
            dut.io.widthSupported.expect(false.B)
          }
        }
      }
    }
  }

  for (dlen <- Seq(64, 128, 1024)) {
    it should s"reject quantization on unsupported DLEN=$dlen without rejecting ordinary arithmetic" in {
      test(new EvuFpQuantLegalityHarness(params(dlen))).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.base.poke(1.U)
        dut.io.vl.poke(64.U)
        dut.io.lmul.poke(0.U)
        for (instr <- Seq(scalarQuant, vectorQuant)) {
          dut.io.instr.poke(instr.U)
          dut.io.targetSupported.expect(false.B)
        }
        dut.io.instr.poke("h6000200b".U) // Ordinary FP add has no CUTE target.
        dut.io.base.poke(((BigInt(1) << 64) - 1).U)
        dut.io.vl.poke(128.U)
        dut.io.targetSupported.expect(true.B)
      }
    }
  }
}
