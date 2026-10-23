package evu

import chisel3._
import chiseltest._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common.VectorParams
import xiangshan.evu.exu._

class EvuFpFlagsSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU FMA exception flags"

  private implicit val p: Parameters = EvuVectorParameters(
    Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 512, 256, 128, VectorParams.lgvParams)

  for (segmented <- Seq(false, true)) {
    it should s"keep NV, OF, UF and NX on every FP16/FP32 byte (segmented=$segmented)" in {
      test(if (segmented) new SegmentedFMAPipe(4) else new TandemFMAPipe(4)) { dut =>
        dut.io.valid.poke(false.B)
        dut.io.frm.poke(0.U)
        dut.io.addsub.poke(false.B)
        dut.io.mul.poke(false.B)
        dut.io.op.poke(0.U)
        for (sew <- Seq(1, 2)) {
          val width = 8 << sew
          val count = 64 / width
          val one = if (sew == 1) 0x3c00L else 0x3f800000L
          val two = if (sew == 1) 0x4000L else 0x40000000L
          val tiny = if (sew == 1) 0x1000L else 0x33800000L
          val largest = if (sew == 1) 0x7bffL else 0x7f7fffffL
          val smallestNormal = if (sew == 1) 0x0400L else 0x00800000L
          val infinity = if (sew == 1) 0x7c00L else 0x7f800000L
          val nan = if (sew == 1) 0x7e00L else 0x7fc00000L
          val cases = Seq(
            (one, one, 0L, one, 0),
            (one, one, tiny, one, 1),
            (largest, two, 0L, infinity, 5),
            (smallestNormal, smallestNormal, 0L, 0L, 3),
            (infinity, 0L, 0L, nan, 16))
          def pack(values: Seq[Long]): BigInt = values.zipWithIndex.foldLeft(BigInt(0)) {
            case (bits, (value, lane)) => bits | (BigInt(value) << (width * lane))
          }
          for (lane <- 0 until count; (a, b, c, result, flags) <- cases) {
            dut.io.a_eew.poke(sew.U)
            dut.io.b_eew.poke(sew.U)
            dut.io.c_eew.poke(sew.U)
            dut.io.out_eew.poke(sew.U)
            dut.io.a.poke(pack(Seq.fill(count)(one).updated(lane, a)).U)
            dut.io.b.poke(pack(Seq.fill(count)(one).updated(lane, b)).U)
            dut.io.c.poke(pack(Seq.fill(count)(0L).updated(lane, c)).U)
            dut.io.valid.poke(true.B)
            dut.clock.step()
            dut.io.valid.poke(false.B)
            dut.clock.step(2)
            withClue(s"sew=$sew lane=$lane flags=$flags: ") {
              dut.io.out.expect(pack(Seq.fill(count)(one).updated(lane, result)).U)
              for (byte <- 0 until 8)
                dut.io.exc(byte).expect((if (byte / (1 << sew) == lane) flags else 0).U)
            }
            dut.clock.step()
          }
        }
      }
    }
  }
}
