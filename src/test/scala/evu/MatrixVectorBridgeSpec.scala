package evu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class MatrixVectorBridgeSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "MatrixVectorBridge"

  for (dim <- Seq(8, 16)) {
    it should s"preserve 8x8 microtiles and mask upper lanes of 512-bit beats for a $dim-row tile" in {
      val ep = EvuParams(enable = true)
      test(new MatrixVectorBridge(dim * dim * 32, ep)) { dut =>
        val input = (0 until dim * dim).foldLeft(BigInt(0)) { (data, i) =>
          data | (BigInt(i + 1) << (32 * i))
        }
        dut.io.in.valid.poke(false.B)
        dut.io.out.ready.poke(false.B)
        dut.io.in.bits.data.poke(input.U)
        dut.io.in.bits.tileId.poke(17.U)
        dut.io.in.bits.kComplete.poke(true.B)
        dut.io.in.bits.taskLast.poke(true.B)
        dut.io.in.bits.scaleTag.poke(1.U)
        dut.io.in.bits.biasTag.poke(2.U)
        dut.io.in.valid.poke(true.B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.clock.step()
        val beatsPerRow = dim / 8
        for (beat <- 0 until dim * beatsPerRow) {
          val row = beat / beatsPerRow
          val col = (beat % beatsPerRow) * 8
          val expected = (0 until 8).foldLeft(BigInt(0)) { (data, lane) =>
            data | (BigInt(row * dim + col + lane + 1) << (32 * lane))
          }
          dut.io.out.ready.poke(false.B)
          for (_ <- 0 until 2) {
            dut.io.out.valid.expect(true.B)
            dut.io.out.bits.data.expect(expected.U)
            dut.io.out.bits.row.expect(row.U)
            dut.io.out.bits.col.expect(col.U)
            dut.io.out.bits.validLanes.expect(255.U)
            dut.io.out.bits.microblockId.expect(((row / 8) * beatsPerRow + col / 8).U)
            dut.io.out.bits.microblockBeat.expect((row % 8).U)
            dut.io.out.bits.microblockBeats.expect(8.U)
            dut.io.out.bits.taskLast.expect((beat == dim * beatsPerRow - 1).B)
            dut.clock.step()
          }
          dut.io.out.ready.poke(true.B)
          dut.clock.step()
        }
        dut.io.out.valid.expect(false.B)
      }
    }
  }

  it should "hold K-incomplete tiles and serialize complete tiles with double buffering" in {
    val ep = EvuParams(enable = true, vlenBits = 1024)
    test(new MatrixVectorBridge(tileBits = 512, ep)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()

      dut.io.in.bits.data.poke("h0000000000000002000000000000000100000000000000040000000000000003".U)
      dut.io.in.bits.tileId.poke(7.U)
      dut.io.in.bits.kComplete.poke(false.B)
      dut.io.in.bits.taskLast.poke(false.B)
      dut.io.in.bits.scaleTag.poke(0.U)
      dut.io.in.bits.biasTag.poke(0.U)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(false.B)

      dut.io.in.bits.data.poke("h0000000000000006000000000000000500000000000000080000000000000007".U)
      dut.io.in.bits.tileId.poke(8.U)
      dut.io.in.bits.kComplete.poke(true.B)
      dut.io.in.bits.taskLast.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.kComplete.expect(true.B)
      dut.clock.step()
      dut.io.out.bits.kComplete.expect(true.B)
      dut.io.out.bits.tileId.expect(8.U)
    }
  }
}
