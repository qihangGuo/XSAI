package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common._

class EvuDiagnosticHarness(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val op = Input(new ExecuteMicroOpWithData(1))
    val kind = Output(UInt(EvuOpClass.width.W))
    val elements16 = Output(UInt(7.W))
    val elements32 = Output(UInt(7.W))
  })
  io.kind := EvuOpClass(io.op)
  io.elements16 := EvuOpClass.elements(io.op, 1)
  io.elements32 := EvuOpClass.elements(io.op, 2)
}

class EvuDiagnosticSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "EVU executed-work diagnostics"
  it should "distinguish E0 and vector work and count actual reduction-fold lanes" in {
    val base = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    implicit val p: Parameters = EvuVectorParameters(base, 4096, 512, 512, VectorParams.lgvParams)
    test(new EvuDiagnosticHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      def clear(x: Data): Unit = x match {
        case b: Bool => b.poke(false.B)
        case u: UInt => u.poke(0.U)
        case s: SInt => s.poke(0.S)
        case v: Vec[_] => v.foreach(clear)
        case b: Bundle => b.elements.values.foreach(clear)
      }
      clear(dut.io.op)
      val op = dut.io.op
      op.funct3.poke(VectorConsts.OPFVV)
      op.funct6.poke(OPFFunct6.fmul.litValue.U)
      op.head.poke(true.B); op.tail.poke(true.B)
      op.vd_eew.poke(2.U)
      op.eidx_mask.poke(15.U); op.wmask.poke(15.U)
      dut.io.kind.expect(EvuOpClass.names.indexOf("e0_mul").U)
      dut.io.elements32.expect(1.U)
      op.eidx_mask.poke(((BigInt(1) << 64) - 1).U)
      op.wmask.poke(((BigInt(1) << 64) - 1).U)
      dut.io.kind.expect(EvuOpClass.names.indexOf("mul").U)
      dut.io.elements32.expect(16.U)
      dut.io.elements16.expect(32.U)
      op.funct6.poke(OPFFunct6.fredusum.litValue.U)
      op.acc.poke(true.B); op.acc_fold.poke(true.B)
      // Eight FP32 lanes participate in the first 512-bit fold, although the
      // architectural reduction eventually writes only element zero.
      op.wmask.poke(((BigInt(1) << 32) - 1).U)
      dut.io.kind.expect(EvuOpClass.names.indexOf("red_sum").U)
      dut.io.elements32.expect(8.U)
      op.wmask.poke(0xf00f.U) // Two active FP32 lanes with a masked gap.
      dut.io.elements32.expect(2.U)
      op.wmask.poke(0x33.U)
      dut.io.elements16.expect(2.U)
      dut.io.elements32.expect(0.U)
    }
  }
}
