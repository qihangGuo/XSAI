package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuOperandCollectionSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU multi-cycle operand collection"
  private val ep = EvuParams(enable = true, vlenBits = 4096)
  private def words(value: Int): BigInt = (0 until 8).map(i => BigInt(value) << (32*i)).reduce(_ | _)
  private def clear(x: Data): Unit = x match {
    case b: Bool => b.poke(false.B)
    case u: UInt => u.poke(0.U)
    case s: SInt => s.poke(0.S)
    case v: Vec[_] => v.foreach(clear)
    case b: Bundle => b.elements.values.foreach(clear)
  }
  private def command(bits: EvuCommand): Unit = {
    clear(bits)
    bits.opcode.poke(EvuOpcode.FpQuantToCute)
    bits.sew.poke(2.U)
    bits.vd.poke(17.U)
    bits.vs2.poke(9.U)
    bits.vl.poke(64.U)
  }
  private def await(dut: Module, ready: Bool): Unit = {
    var cycles = 0
    while (!ready.peek().litToBoolean && cycles < 30) { dut.clock.step(); cycles += 1 }
    ready.expect(true.B)
  }

  it should "route both matrix scales through the shared ports before and after quantization" in {
    test(new EvuSpecialSequencer(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.invalidate.poke(false.B)
      dut.io.command.valid.poke(false.B)
      command(dut.io.command.bits)
      dut.io.completion.ready.poke(false.B)
      dut.io.cuteWrite.ready.poke(false.B)
      dut.io.matrix.in.valid.poke(false.B)
      clear(dut.io.matrix.in.bits)
      dut.io.matrix.out.ready.poke(false.B)
      dut.io.vrf.read.foreach(_.ready.poke(false.B))
      dut.io.vrf.readData.foreach(_.poke(0.U))

      def pack(values: Seq[BigInt]): BigInt = values.zipWithIndex.foldLeft(BigInt(0)) {
        case (value, (word, lane)) => value | ((word & 0xffffffffL) << (32 * lane))
      }
      val input = pack((0 until 8).map(i => BigInt(i - 3)))
      val expected = pack((0 until 8).map(i =>
        BigInt(java.lang.Float.floatToRawIntBits(((i - 3) * 8).toFloat).toLong)))

      def sendMatrix(): Unit = {
        clear(dut.io.matrix.in.bits)
        dut.io.matrix.in.bits.data.poke(input.U)
        dut.io.matrix.in.bits.tileId.poke(0x11.U)
        dut.io.matrix.in.bits.sew.poke(2.U)
        dut.io.matrix.in.bits.kComplete.poke(true.B)
        dut.io.matrix.in.bits.taskLast.poke(true.B)
        dut.io.matrix.in.bits.scaleTag.poke(20.U)
        dut.io.matrix.in.bits.biasTag.poke(21.U)
        dut.io.matrix.in.bits.row.poke(3.U)
        dut.io.matrix.in.bits.tileDim.poke(8.U)
        dut.io.matrix.in.bits.validLanes.poke(255.U)
        dut.io.matrix.in.bits.microblockBeats.poke(8.U)
        dut.io.matrix.in.valid.poke(true.B)
        await(dut, dut.io.matrix.in.ready)
        dut.clock.step()
        dut.io.matrix.in.valid.poke(false.B)
      }

      def matrixResult(order: Seq[Int]): Unit = {
        dut.io.invalidate.poke(true.B)
        dut.clock.step()
        dut.io.invalidate.poke(false.B)
        sendMatrix()
        for (_ <- 0 until 3) {
          dut.io.vrf.read(0).valid.expect(false.B)
          for (port <- 1 to 2) {
            dut.io.vrf.read(port).valid.expect(true.B)
            dut.io.vrf.read(port).bits.addr.expect((19 + port).U)
            dut.io.vrf.read(port).bits.chunk.expect(1.U)
          }
          dut.clock.step()
        }
        // Model independent bank/response delays in both possible orders.
        for ((port, index) <- order.zipWithIndex) {
          dut.io.vrf.read(port).valid.expect(true.B)
          dut.io.vrf.readData(port).poke(words(if (port == 1) 0x40000000 else 0x40800000).U)
          dut.io.vrf.read(port).ready.poke(true.B)
          dut.clock.step()
          dut.io.vrf.read(port).ready.poke(false.B)
          dut.io.vrf.readData(port).poke(0.U)
          if (index == 0) {
            for (_ <- 0 until 3) {
              dut.io.vrf.read(port).valid.expect(false.B)
              dut.io.vrf.read(order(1)).valid.expect(true.B)
              dut.io.matrix.out.valid.expect(false.B)
              dut.clock.step()
            }
          }
        }
        await(dut, dut.io.matrix.out.valid)
        for (_ <- 0 until 4) {
          dut.io.matrix.out.valid.expect(true.B)
          dut.io.matrix.out.bits.data.expect(expected.U)
          dut.io.matrix.out.bits.tileId.expect(0x11.U)
          dut.io.matrix.out.bits.row.expect(3.U)
          dut.io.matrix.out.bits.taskLast.expect(true.B)
          dut.io.matrix.fflags.expect(0.U)
          dut.io.vrf.read.foreach(_.valid.expect(false.B))
          dut.io.cuteWrite.valid.expect(false.B)
          dut.io.completion.valid.expect(false.B)
          dut.io.busy.expect(true.B)
          dut.clock.step()
        }
        dut.io.matrix.out.ready.poke(true.B)
        dut.clock.step()
        dut.io.matrix.out.ready.poke(false.B)
        dut.io.busy.expect(false.B)
      }

      matrixResult(Seq(2, 1))
      command(dut.io.command.bits)
      dut.io.command.bits.vl.poke(8.U)
      dut.io.command.valid.poke(true.B)
      await(dut, dut.io.command.ready)
      dut.clock.step()
      dut.io.command.valid.poke(false.B)
      for (port <- Seq(1, 0)) {
        dut.io.vrf.read(port).valid.expect(true.B)
        dut.io.vrf.read(port).bits.addr.expect((if (port == 0) 9 else 17).U)
        dut.io.vrf.read(port).bits.chunk.expect(0.U)
        dut.io.vrf.read(2).valid.expect(false.B)
        dut.io.vrf.readData(port).poke(words(if (port == 0) 0x3f000000 else 0x41000000).U)
        dut.io.vrf.read(port).ready.poke(true.B)
        dut.clock.step()
        dut.io.vrf.read(port).ready.poke(false.B)
      }
      await(dut, dut.io.cuteWrite.valid)
      dut.io.cuteWrite.bits.data.expect(BigInt("0404040404040404", 16).U)
      dut.io.cuteWrite.bits.byteMask.expect(255.U)
      dut.io.completion.valid.expect(false.B)
      dut.io.cuteWrite.ready.poke(true.B)
      dut.clock.step()
      dut.io.cuteWrite.ready.poke(false.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.exception.expect(false.B)
      dut.io.completion.bits.fflags.expect(1.U) // RTZ conversion of 4.5 sets NX.
      dut.io.completion.ready.poke(true.B)
      dut.clock.step()
      dut.io.completion.ready.poke(false.B)
      dut.io.busy.expect(false.B)
      matrixResult(Seq(1, 2))

      dut.io.invalidate.poke(true.B)
      dut.clock.step()
      dut.io.invalidate.poke(false.B)
      sendMatrix()
      dut.io.vrf.read(1).valid.expect(true.B)
      dut.io.vrf.read(2).valid.expect(true.B)
      dut.io.vrf.readData(1).poke(words(0x40000000).U)
      dut.io.vrf.read(1).ready.poke(true.B)
      dut.clock.step()
      dut.io.vrf.read(1).ready.poke(false.B)
      dut.io.vrf.read(1).valid.expect(false.B)
      dut.io.vrf.read(2).valid.expect(true.B)
      dut.io.flush.poke(true.B)
      dut.io.vrf.read.foreach(_.valid.expect(false.B))
      dut.clock.step()
      dut.io.flush.poke(false.B)
      for (_ <- 0 until 4) {
        dut.io.matrix.out.valid.expect(false.B)
        dut.io.cuteWrite.valid.expect(false.B)
        dut.io.completion.valid.expect(false.B)
        dut.io.busy.expect(false.B)
        dut.clock.step()
      }
    }
  }

  it should "prefetch both operands independently while CUTE holds the first result" in {
    test(new EvuSpecialSequencer(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.invalidate.poke(false.B)
      dut.io.command.valid.poke(false.B)
      dut.io.completion.ready.poke(false.B)
      dut.io.cuteWrite.ready.poke(false.B)
      dut.io.matrix.in.valid.poke(false.B)
      clear(dut.io.matrix.in.bits)
      dut.io.matrix.out.ready.poke(true.B)
      dut.io.vrf.read.foreach(_.ready.poke(false.B))
      dut.io.vrf.readData.foreach(_.poke(0.U))
      command(dut.io.command.bits)
      dut.io.command.valid.poke(true.B)
      await(dut, dut.io.command.ready)
      dut.clock.step()
      dut.io.command.valid.poke(false.B)
      // Fill both probability slots before providing either inverse scale.
      for (chunk <- 0 until 2) {
        dut.io.vrf.read(0).valid.expect(true.B)
        dut.io.vrf.read(0).bits.chunk.expect(chunk.U)
        dut.io.vrf.read(0).ready.poke(true.B)
        dut.io.vrf.readData(0).poke(words(0x3f800000 + chunk * 0x00800000).U)
        dut.clock.step()
      }
      dut.io.vrf.read(0).ready.poke(false.B)
      dut.io.vrf.read(0).valid.expect(false.B)
      dut.io.cuteWrite.valid.expect(false.B)
      var inputReads = 2
      var scaleReads = 0
      var writes = 0
      var cycles = 0
      while (!dut.io.completion.valid.peek().litToBoolean && cycles < 160) {
        dut.io.cuteWrite.ready.poke((cycles >= 25).B)
        for (port <- 0 until 2) {
          val chunk = dut.io.vrf.read(port).bits.chunk.peek().litValue.toInt
          val ready = if (port == 0) cycles % 3 == 0 else cycles % 4 == 1
          dut.io.vrf.read(port).ready.poke(ready.B)
          val value = if (port == 0) 0x3f800000 + chunk * 0x00800000 else 0x3f800000
          dut.io.vrf.readData(port).poke(words(value).U)
          if (dut.io.vrf.read(port).valid.peek().litToBoolean && ready) {
            if (port == 0) inputReads += 1 else scaleReads += 1
          }
        }
        if (cycles < 25) dut.io.completion.valid.expect(false.B)
        if (dut.io.cuteWrite.valid.peek().litToBoolean && cycles >= 25) {
          val n = math.min(127, 1 << writes)
          val packed = (0 until 8).map(i => BigInt(n) << (8 * i)).reduce(_ | _)
          dut.io.cuteWrite.bits.data.expect((packed << (64 * (writes % 4))).U)
          writes += 1
        }
        if (cycles == 24) {
          // The next groups have been read despite no accepted CUTE write.
          inputReads should be > 2
          scaleReads should be > 1
        }
        dut.clock.step()
        cycles += 1
      }
      writes shouldBe 8
      inputReads shouldBe 8
      scaleReads shouldBe 8
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.exception.expect(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.clock.step()
      dut.io.completion.ready.poke(false.B)
      // Flush queued operands and occupied arithmetic stages together.
      command(dut.io.command.bits)
      dut.io.command.valid.poke(true.B)
      await(dut, dut.io.command.ready)
      dut.clock.step()
      dut.io.command.valid.poke(false.B)
      dut.io.vrf.read.foreach(_.ready.poke(true.B))
      dut.io.cuteWrite.ready.poke(false.B)
      await(dut, dut.io.cuteWrite.valid)
      dut.io.flush.poke(true.B)
      dut.io.cuteWrite.valid.expect(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.cuteWrite.ready.poke(true.B)
      for (_ <- 0 until 6) {
        dut.io.cuteWrite.valid.expect(false.B)
        dut.io.completion.valid.expect(false.B)
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
    }
  }
}
