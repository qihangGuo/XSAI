package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuHbl2LineAdapterSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EvuHbl2LineAdapter"

  it should "preserve masked bytes across two lines and hold overlapping requests until completion" in {
    val ep = EvuParams(enable = true)
    test(new EvuHbl2LineAdapter(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.client.req.valid.poke(false.B)
      dut.io.client.resp.ready.poke(false.B)
      dut.io.memory.req.ready.poke(false.B)
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.memory.resp.bits.data.poke(0.U)
      dut.io.memory.resp.bits.error.poke(false.B)
      dut.io.memory.resp.bits.fault.poke(EvuFault.None)
      dut.io.memory.resp.bits.tag.poke(0.U)
      val lineMask = (BigInt(1) << 512) - 1
      val byteMask = (BigInt(1) << 64) - 1
      val old0 = BigInt("aa" * 64, 16)
      val old1 = BigInt("bb" * 64, 16)
      val payload = BigInt("0102030405060708", 16)

      def await(signal: Bool): Unit = {
        var cycles = 0
        while (!signal.peek().litToBoolean && cycles < 100) { dut.clock.step(); cycles += 1 }
        signal.peek().litToBoolean shouldBe true
      }
      def request(addr: Int, data: BigInt, mask: BigInt, bytes: Int, write: Boolean, tag: Int): Unit = {
        dut.io.client.req.bits.addr.poke(addr.U)
        dut.io.client.req.bits.data.poke(data.U)
        dut.io.client.req.bits.mask.poke(mask.U)
        dut.io.client.req.bits.bytes.poke(bytes.U)
        dut.io.client.req.bits.write.poke(write.B)
        dut.io.client.req.bits.tag.poke(tag.U)
        dut.io.client.req.valid.poke(true.B)
        await(dut.io.client.req.ready)
        dut.clock.step()
        dut.io.client.req.valid.poke(false.B)
      }
      def busRequest(addr: Int, write: Boolean, data: BigInt = 0, logicalMask: BigInt = (BigInt(1) << 64) - 1): Unit = {
        await(dut.io.memory.req.valid)
        dut.io.memory.req.bits.addr.expect(addr.U)
        dut.io.memory.req.bits.bytes.expect(64.U)
        dut.io.memory.req.bits.mask.expect((if (write) logicalMask else byteMask).U)
        dut.io.memory.req.bits.write.expect(write.B)
        if (write) dut.io.memory.req.bits.data.expect(data.U)
        dut.clock.step(2)
        dut.io.memory.req.valid.expect(true.B)
        dut.io.memory.req.ready.poke(true.B)
        dut.clock.step()
        dut.io.memory.req.ready.poke(false.B)
      }
      def response(data: BigInt): Unit = {
        dut.io.memory.resp.bits.data.poke(data.U)
        dut.io.memory.resp.valid.poke(true.B)
        await(dut.io.memory.resp.ready)
        dut.clock.step()
        dut.io.memory.resp.valid.poke(false.B)
      }

      // Eight bytes starting at 60 straddle two cache lines. Only alternating
      // byte enables are set, so neither padding nor old data may be lost.
      request(60, payload, 0x55, 8, write = true, tag = 0)
      busRequest(0, write = false)
      response(old0)
      val mask0 = BigInt("00ff00ff", 16) << (60 * 8)
      val merged0 = ((old0 & ~mask0) | ((payload << (60 * 8)) & mask0)) & lineMask
      busRequest(0, write = true, merged0, BigInt(5) << 60)
      response(0)
      busRequest(64, write = false)
      response(old1)
      val mask1 = BigInt("00ff00ff", 16)
      val merged1 = (old1 & ~mask1) | ((payload >> 32) & mask1)
      busRequest(64, write = true, merged1, BigInt(5))
      dut.io.client.resp.valid.expect(false.B)
      response(0)
      await(dut.io.client.resp.valid)
      dut.io.client.resp.bits.error.expect(false.B)

      // A conflicting reader has a different tag but must wait for delivery
      // of the store response (the line lock covers all RMW subtransactions).
      dut.io.client.req.bits.tag.poke(1.U)
      dut.io.client.req.bits.write.poke(false.B)
      dut.io.client.req.valid.poke(true.B)
      dut.clock.step(3)
      dut.io.client.req.ready.expect(false.B)
      dut.io.client.resp.ready.poke(true.B)
      dut.clock.step()
      await(dut.io.client.req.ready)
      dut.clock.step()
      dut.io.client.req.valid.poke(false.B)
      busRequest(0, write = false)
      dut.io.memory.resp.bits.tag.poke(1.U)
      response(merged0)
      busRequest(64, write = false)
      response(merged1)
      await(dut.io.client.resp.valid)
      val combined = ((merged1 << 512) | merged0) >> (60 * 8)
      dut.io.client.resp.bits.data.expect((combined & lineMask).U)
      dut.clock.step()
      dut.io.busy.expect(false.B)
    }
  }

  it should "drain a queued request on flush without reusing its source or returning a stale response" in {
    val ep = EvuParams(enable = true)
    test(new EvuHbl2LineAdapter(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.client.resp.ready.poke(true.B)
      dut.io.memory.req.ready.poke(false.B)
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.memory.resp.bits.data.poke(0.U)
      dut.io.memory.resp.bits.error.poke(false.B)
      dut.io.memory.resp.bits.fault.poke(EvuFault.None)
      dut.io.memory.resp.bits.tag.poke(2.U)
      dut.io.client.req.bits.addr.poke(128.U)
      dut.io.client.req.bits.data.poke(0.U)
      dut.io.client.req.bits.mask.poke(0.U)
      dut.io.client.req.bits.bytes.poke(32.U)
      dut.io.client.req.bits.write.poke(false.B)
      dut.io.client.req.bits.tag.poke(2.U)
      dut.io.client.req.valid.poke(true.B)
      dut.io.client.req.ready.expect(true.B)
      dut.clock.step()
      dut.io.client.req.valid.poke(false.B)
      dut.clock.step(3)
      dut.io.memory.req.valid.expect(true.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.client.req.valid.poke(true.B)
      dut.io.client.req.ready.expect(false.B)
      dut.io.memory.req.valid.expect(true.B)
      dut.io.memory.req.bits.tag.expect(2.U)
      dut.io.memory.req.ready.poke(true.B)
      dut.clock.step()
      dut.io.memory.resp.valid.poke(true.B)
      dut.io.memory.resp.ready.expect(true.B)
      dut.io.client.resp.valid.expect(false.B)
      dut.clock.step()
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.client.req.ready.expect(true.B)
      dut.io.client.req.valid.poke(false.B)
      dut.io.busy.expect(false.B)
    }
  }
}
