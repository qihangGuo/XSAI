package evu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class MatrixVectorBridgeSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "MatrixVectorBridge"

  it should "hold K-incomplete tiles and serialize complete tiles with double buffering" in {
    val ep = EvuParams(enable = true, vlenBits = 512)
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
