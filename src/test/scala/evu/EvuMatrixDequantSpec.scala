package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuMatrixDequantSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EvuMatrixDequant"

  private val ep = EvuParams(enable = true)
  private def bits(value: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(value).toLong & 0xffffffffL)
  private def pack(values: Seq[BigInt]): BigInt = values.zipWithIndex.foldLeft(BigInt(0)) {
    case (sum, (value, lane)) => sum | ((value & 0xffffffffL) << (lane * 32))
  }

  private class Driver(dut: EvuMatrixDequant) {
    dut.io.flush.poke(false.B)
    dut.io.invalidate.poke(false.B)
    dut.io.startAllowed.poke(true.B)
    dut.io.matrix.in.valid.poke(false.B)
    dut.io.matrix.out.ready.poke(false.B)
    for (i <- 0 until 2) {
      dut.io.read(i).ready.poke(false.B)
      dut.io.readData(i).poke(0.U)
    }

    def await(signal: Bool): Unit = {
      var cycles = 0
      while (!signal.peek().litToBoolean && cycles < 30) { dut.clock.step(); cycles += 1 }
      withClue(s"No handshake after $cycles cycles: ") { signal.expect(true.B) }
    }

    def poke(input: Seq[Int], tile: Int = 0, row: Int = 0, col: Int = 0, dim: Int = 8): Unit = {
      val in = dut.io.matrix.in.bits
      in.data.poke(pack(input.map(i => BigInt(i) & 0xffffffffL)).U)
      in.tileId.poke(tile.U)
      in.microblockId.poke(row.U)
      in.sew.poke(2.U)
      in.kComplete.poke(true.B)
      in.taskLast.poke(true.B)
      in.scaleTag.poke(1.U)
      in.biasTag.poke(2.U)
      in.row.poke(row.U)
      in.col.poke(col.U)
      in.tileDim.poke(dim.U)
      in.validLanes.poke(255.U)
      in.microblockBeat.poke(0.U)
      in.microblockBeats.poke(8.U)
    }

    def send(input: Seq[Int], tile: Int = 0, row: Int = 0, col: Int = 0, dim: Int = 8): Unit = {
      poke(input, tile, row, col, dim)
      dut.io.matrix.in.valid.poke(true.B)
      await(dut.io.matrix.in.ready)
      dut.clock.step()
      dut.io.matrix.in.valid.poke(false.B)
    }

    def operand(port: Int, chunk: Int, values: Seq[BigInt]): Unit = {
      await(dut.io.read(port).valid)
      dut.io.read(port).bits.addr.expect((port + 1).U)
      dut.io.read(port).bits.chunk.expect(chunk.U)
      for (_ <- 0 until 3) {
        dut.clock.step()
        dut.io.read(port).valid.expect(true.B)
        dut.io.read(port).bits.chunk.expect(chunk.U)
      }
      dut.io.readData(port).poke(pack(values).U)
      dut.io.read(port).ready.poke(true.B)
      dut.clock.step()
      dut.io.read(port).ready.poke(false.B)
      dut.io.readData(port).poke(0.U)
    }

    def result(expected: Seq[BigInt], flags: Int = 0, tile: Int = 0, row: Int = 0, col: Int = 0): Unit = {
      await(dut.io.matrix.out.valid)
      for (_ <- 0 until 4) {
        dut.io.matrix.out.bits.data.expect(pack(expected).U)
        dut.io.matrix.out.bits.tileId.expect(tile.U)
        dut.io.matrix.out.bits.row.expect(row.U)
        dut.io.matrix.out.bits.col.expect(col.U)
        dut.io.matrix.out.bits.taskLast.expect(true.B)
        dut.io.matrix.fflags.expect(flags.U)
        dut.clock.step()
      }
      dut.io.matrix.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.matrix.out.ready.poke(false.B)
    }
  }

  it should "select scales by matrix coordinates and preserve buffered beats under backpressure" in {
    test(new EvuMatrixDequant(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val driver = new Driver(dut)
      val input = Seq(-16, -8, -1, 0, 1, 2, 64, 127)
      val cases = Seq((0, 0, 0, 8), (0x21, 3, 0, 8), (0x23, 9, 8, 16), (0xff, 7, 0, 8))
      for ((tile, row, col, dim) <- cases) {
        val m = (tile & 15) * dim + col
        val n = (tile >> 4) * dim + row
        val scaleA = (0 until 8).map(i => (m / 8 * 8 + i + 1) * 0.25f)
        val scaleB = (0 until 8).map(i => (n / 8 * 8 + i + 1) * 0.125f)
        driver.send(input, tile, row, col, dim)
        driver.operand(0, m / 8, scaleA.map(bits))
        driver.operand(1, n / 8, scaleB.map(bits))
        // Change live producer metadata before the previous beat is consumed.
        dut.io.matrix.in.bits.tileId.poke(0.U)
        dut.io.matrix.in.bits.row.poke(0.U)
        driver.result(input.zip(scaleA).map { case (a, scale) => bits((a.toFloat * scale) * scaleB(n % 8)) },
          tile = tile, row = row, col = col)
      }
      dut.io.busy.expect(false.B)
    }
  }

  it should "report conversion and invalid-operation flags from the selected arithmetic" in {
    test(new EvuMatrixDequant(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val driver = new Driver(dut)
      val input = Seq.fill(8)(16777217)
      driver.send(input)
      driver.operand(0, 0, Seq.fill(8)(bits(1.5f)))
      driver.operand(1, 0, Seq.fill(8)(bits(0.5f)))
      driver.result(Seq.fill(8)(bits((16777217.toFloat * 1.5f) * 0.5f)), flags = 1)
      // An ordinary EVU write changes the same cached source registers.
      dut.io.invalidate.poke(true.B)
      dut.clock.step()
      dut.io.invalidate.poke(false.B)
      driver.send(Seq.fill(8)(1))
      driver.operand(0, 0, Seq.fill(8)(BigInt(0x7f800000L)))
      driver.operand(1, 0, Seq.fill(8)(bits(0.0f)))
      driver.result(Seq.fill(8)(BigInt(0x7fc00000L)), flags = 16)
      // Invalid operations in masked-off matrix lanes must not publish NV.
      dut.io.invalidate.poke(true.B)
      dut.clock.step()
      dut.io.invalidate.poke(false.B)
      driver.poke(Seq.fill(4)(2) ++ Seq.fill(4)(0), dim = 4)
      dut.io.matrix.in.bits.validLanes.poke(15.U)
      dut.io.matrix.in.valid.poke(true.B)
      driver.await(dut.io.matrix.in.ready)
      dut.clock.step()
      dut.io.matrix.in.valid.poke(false.B)
      driver.operand(0, 0, Seq.fill(4)(bits(1f)) ++ Seq.fill(4)(BigInt(0x7f800000L)))
      driver.operand(1, 0, Seq.fill(8)(bits(1f)))
      driver.result(Seq.fill(4)(bits(2f)) ++ Seq.fill(4)(bits(0f)))
    }
  }

  it should "reuse just the current chunks, stream hits every cycle and invalidate changed scales" in {
    test(new EvuMatrixDequant(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val driver = new Driver(dut)
      val a = (1 to 8).map(_.toFloat)
      val b = (1 to 8).map(_ * 0.5f)
      driver.send(Seq.fill(8)(2))
      driver.operand(1, 0, b.map(bits)) // Opposite return order must also work.
      driver.operand(0, 0, a.map(bits))
      driver.result(a.map(x => bits(x)))
      dut.io.matrix.out.ready.poke(true.B)
      var sent = 0
      var received = 0
      var previousOutput = -1
      for (cycle <- 0 until 30) {
        val n = sent % 8
        driver.poke(Seq.fill(8)(sent + 1), row = n)
        dut.io.matrix.in.valid.poke((sent < 16).B)
        dut.io.read.foreach(_.valid.expect(false.B))
        val accepted = sent < 16 && dut.io.matrix.in.ready.peek().litToBoolean
        if (dut.io.matrix.out.valid.peek().litToBoolean) {
          dut.io.matrix.out.bits.row.expect((received % 8).U)
          dut.io.matrix.out.bits.data.expect(pack(a.map(x => bits((received + 1).toFloat * x * b(received % 8)))).U)
          dut.io.matrix.fflags.expect(0.U)
          if (received > 0) cycle shouldBe (previousOutput + 1)
          previousOutput = cycle
          received += 1
        }
        dut.clock.step()
        if (accepted) sent += 1
      }
      sent shouldBe 16
      received shouldBe 16
      dut.io.matrix.in.valid.poke(false.B)
      dut.io.matrix.out.ready.poke(false.B)
      // Invalidate without flushing in-flight work. The next beat must miss.
      dut.io.invalidate.poke(true.B)
      dut.clock.step()
      dut.io.invalidate.poke(false.B)
      driver.send(Seq.fill(8)(3))
      driver.operand(0, 0, Seq.fill(8)(bits(2f)))
      driver.operand(1, 0, Seq.fill(8)(bits(0.25f)))
      driver.result(Seq.fill(8)(bits(1.5f)))
    }
  }

  it should "hold a full output pipeline and discard every buffered beat on flush" in {
    test(new EvuMatrixDequant(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val driver = new Driver(dut)
      driver.send(Seq.fill(8)(2))
      driver.operand(0, 0, Seq.fill(8)(bits(1f)))
      driver.operand(1, 0, Seq.fill(8)(bits(1f)))
      for (i <- 0 until 5) driver.send(Seq.fill(8)(i + 3))
      dut.clock.step(4)
      dut.io.matrix.in.ready.expect(false.B)
      dut.io.matrix.out.valid.expect(true.B)
      dut.io.matrix.out.bits.data.expect(pack(Seq.fill(8)(bits(2f))).U)
      dut.io.flush.poke(true.B)
      dut.io.matrix.out.valid.expect(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.matrix.out.ready.poke(true.B)
      dut.clock.step(5)
      dut.io.busy.expect(false.B)
      dut.io.matrix.out.valid.expect(false.B)
      // Flush also invalidates scales; a new task cannot reuse them.
      dut.io.matrix.out.ready.poke(false.B)
      driver.send(Seq.fill(8)(4))
      driver.operand(0, 0, Seq.fill(8)(bits(0.5f)))
      driver.operand(1, 0, Seq.fill(8)(bits(0.5f)))
      driver.result(Seq.fill(8)(bits(1f)))
    }
  }

  it should "wait for shared-VRF ownership and cancel queued or partially read work on flush" in {
    test(new EvuMatrixDequant(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val driver = new Driver(dut)
      dut.io.startAllowed.poke(false.B)
      driver.send(Seq.fill(8)(9))
      driver.send(Seq.fill(8)(10))
      dut.clock.step(3)
      dut.io.active.expect(false.B)
      dut.io.busy.expect(true.B)
      dut.io.read(0).valid.expect(false.B)
      dut.io.startAllowed.poke(true.B)
      driver.operand(0, 0, Seq.fill(8)(bits(1.0f)))
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.clock.step(3)
      dut.io.busy.expect(false.B)
      dut.io.matrix.out.valid.expect(false.B)
      driver.send(Seq.fill(8)(3))
      driver.operand(0, 0, Seq.fill(8)(bits(2.0f)))
      driver.operand(1, 0, Seq.fill(8)(bits(0.25f)))
      driver.result(Seq.fill(8)(bits(1.5f)))
    }
  }
}
