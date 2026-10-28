package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuMatrixStoreBufferSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EvuMatrixStoreBuffer"

  private val ep = EvuParams(enable = true)
  private val halfMask = (BigInt(1) << 32) - 1
  private val fullMask = (BigInt(1) << 64) - 1

  private def initialize(dut: EvuMatrixStoreBuffer): Unit = {
    dut.io.flush.poke(false.B)
    dut.io.start.poke(true.B)
    dut.io.in.valid.poke(false.B)
    dut.io.memory.req.ready.poke(false.B)
    dut.io.memory.resp.valid.poke(false.B)
    dut.io.memory.resp.bits.data.poke(0.U)
    dut.io.memory.resp.bits.error.poke(false.B)
    dut.io.memory.resp.bits.fault.poke(EvuFault.None)
    dut.io.memory.resp.bits.tag.poke(0.U)
    dut.clock.step()
    dut.io.start.poke(false.B)
  }

  private def await(dut: EvuMatrixStoreBuffer, signal: Bool): Unit = {
    var cycles = 0
    while (!signal.peek().litToBoolean && cycles < 100) { dut.clock.step(); cycles += 1 }
    signal.peek().litToBoolean shouldBe true
  }

  private def beat(dut: EvuMatrixStoreBuffer, addr: Int, data: BigInt, mask: BigInt = halfMask,
                   last: Boolean = false): Unit = {
    dut.io.in.bits.addr.poke(addr.U)
    dut.io.in.bits.data.poke(data.U)
    dut.io.in.bits.mask.poke(mask.U)
    dut.io.in.bits.last.poke(last.B)
    dut.io.in.valid.poke(true.B)
    await(dut, dut.io.in.ready)
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  private def request(dut: EvuMatrixStoreBuffer): (Int, BigInt, BigInt, Int) = {
    await(dut, dut.io.memory.req.valid)
    dut.io.memory.req.bits.bytes.expect(64.U)
    dut.io.memory.req.bits.write.expect(true.B)
    val result = (dut.io.memory.req.bits.addr.peek().litValue.toInt,
      dut.io.memory.req.bits.data.peek().litValue, dut.io.memory.req.bits.mask.peek().litValue,
      dut.io.memory.req.bits.tag.peek().litValue.toInt)
    dut.io.memory.req.ready.poke(true.B)
    dut.clock.step()
    dut.io.memory.req.ready.poke(false.B)
    result
  }

  private def ack(dut: EvuMatrixStoreBuffer, tag: Int, error: Boolean = false): Unit = {
    dut.io.memory.resp.bits.tag.poke(tag.U)
    dut.io.memory.resp.bits.error.poke(error.B)
    dut.io.memory.resp.bits.fault.poke(if (error) EvuFault.Access else EvuFault.None)
    dut.io.memory.resp.valid.poke(true.B)
    dut.io.memory.resp.ready.expect(true.B)
    dut.clock.step()
    dut.io.memory.resp.valid.poke(false.B)
  }

  it should "combine nonadjacent halves and issue eight writes before any acknowledgement" in {
    test(new EvuMatrixStoreBuffer(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      val lows = (0 until 8).map(i => BigInt("11" * 32, 16) + i)
      val highs = (0 until 8).map(i => BigInt("aa" * 32, 16) + i)
      for (row <- 0 until 8) { beat(dut, row * 512, lows(row)) }
      dut.clock.step(3)
      dut.io.memory.req.valid.expect(false.B)
      for (row <- 0 until 8) { beat(dut, row * 512 + 32, highs(row), last = row == 7) }
      val writes = (0 until 8).map(_ => request(dut))
      writes.map(_._4).distinct.size shouldBe 8
      writes.map(_._1).toSet shouldBe (0 until 8).map(_ * 512).toSet
      for ((addr, data, mask, _) <- writes) {
        mask shouldBe fullMask
        data shouldBe ((highs(addr / 512) << 256) | lows(addr / 512))
      }
      dut.io.done.expect(false.B)
      dut.io.busy.expect(true.B)
      for ((_, _, _, tag) <- writes.reverse) { ack(dut, tag) }
      dut.io.done.expect(true.B)
      dut.io.busy.expect(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      dut.io.done.expect(false.B)
      beat(dut, 64, 5, last = true)
      val tail = request(dut)
      tail._3 shouldBe halfMask
      ack(dut, tail._4)
      dut.io.done.expect(true.B)
    }
  }

  it should "split an unaligned masked tail and wait for both acknowledgements" in {
    test(new EvuMatrixStoreBuffer(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      val payload = BigInt("0102030405060708", 16)
      beat(dut, 60, payload, 0x55, last = true)
      val writes = (0 until 2).map(_ => request(dut)).sortBy(_._1)
      writes.head._1 shouldBe 0
      writes.head._3 shouldBe (BigInt(5) << 60)
      writes.head._2 shouldBe ((payload & 0x00ff00ffL) << 480)
      writes.last._1 shouldBe 64
      writes.last._3 shouldBe BigInt(5)
      writes.last._2 shouldBe ((payload >> 32) & 0x00ff00ffL)
      ack(dut, writes.last._4)
      dut.io.done.expect(false.B)
      ack(dut, writes.head._4)
      dut.io.done.expect(true.B)
    }
  }

  it should "keep a line locked until Ack before accepting a later value for the same address" in {
    test(new EvuMatrixStoreBuffer(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      beat(dut, 0, 1)
      beat(dut, 32, 2)
      val first = request(dut)
      beat(dut, 0, 3)
      beat(dut, 32, 4, last = true)
      dut.clock.step(4)
      dut.io.memory.req.valid.expect(false.B)
      dut.io.done.expect(false.B)
      ack(dut, first._4)
      val second = request(dut)
      second._1 shouldBe 0
      second._2 shouldBe ((BigInt(4) << 256) | 3)
      second._3 shouldBe fullMask
      ack(dut, second._4)
      dut.io.done.expect(true.B)
    }
  }

  it should "evict partial lines under pressure without waiting for a missing complement" in {
    val small = ep.copy(matrixStoreEntries = 2)
    test(new EvuMatrixStoreBuffer(small)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      beat(dut, 0, 1)
      beat(dut, 64, 2)
      beat(dut, 128, 3, last = true)
      val first = request(dut)
      first._3 shouldBe halfMask
      ack(dut, first._4)
      val remaining = (0 until 2).map(_ => request(dut))
      (remaining.map(_._1) :+ first._1).toSet shouldBe Set(0, 64, 128)
      for (r <- remaining) { ack(dut, r._4) }
      dut.io.done.expect(true.B)
    }
  }

  it should "report the first fault, drain accepted writes and discard later beats through last" in {
    test(new EvuMatrixStoreBuffer(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      for (row <- 0 until 2) {
        beat(dut, row * 64, 1)
        beat(dut, row * 64 + 32, 2)
      }
      val a = request(dut)
      val b = request(dut)
      dut.io.memory.resp.bits.tag.poke(a._4.U)
      dut.io.memory.resp.bits.error.poke(true.B)
      dut.io.memory.resp.bits.fault.poke(EvuFault.Access)
      dut.io.memory.resp.valid.poke(true.B)
      dut.io.fault.valid.expect(true.B)
      dut.io.fault.bits.addr.expect(a._1.U)
      dut.clock.step()
      dut.io.memory.resp.valid.poke(false.B)
      beat(dut, 128, 3, last = true)
      dut.clock.step(3)
      dut.io.memory.req.valid.expect(false.B)
      dut.io.done.expect(false.B)
      ack(dut, b._4)
      dut.io.done.expect(true.B)
    }
  }

  it should "cancel queued data and restart without writing stale halves" in {
    test(new EvuMatrixStoreBuffer(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      beat(dut, 0, 1)
      beat(dut, 64, 2)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.busy.expect(false.B)
      dut.io.done.expect(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      beat(dut, 32, 9, last = true)
      val r = request(dut)
      r._2 shouldBe (BigInt(9) << 256)
      r._3 shouldBe (halfMask << 32)
      ack(dut, r._4)
      dut.io.done.expect(true.B)
    }
  }
}
