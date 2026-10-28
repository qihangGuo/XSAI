package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuVmuLineCoalescerSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Command-local VMU line coalescing"

  for (issued <- Seq(false, true)) {
    it should s"cancel a ${if (issued) "sent" else "buffered"} owner on flush before reusing its tag" in {
      test(new EvuVmuLineCoalescer(EvuParams(enable = true))).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.flush.poke(false.B)
        dut.io.start.valid.poke(true.B)
        dut.io.start.bits.poke(0.U)
        dut.io.end.poke(false.B)
        dut.io.client.req.valid.poke(false.B)
        dut.io.client.resp.ready.poke(false.B)
        dut.io.memory.req.ready.poke(false.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        dut.clock.step()
        dut.io.start.valid.poke(false.B)
        dut.io.client.req.valid.poke(true.B)
        dut.io.client.req.bits.addr.poke(128.U)
        dut.io.client.req.bits.data.poke(0.U)
        dut.io.client.req.bits.mask.poke(0xffffffffL.U)
        dut.io.client.req.bits.bytes.poke(32.U)
        dut.io.client.req.bits.write.poke(false.B)
        dut.io.client.req.bits.tag.poke(5.U)
        dut.clock.step()
        dut.io.client.req.valid.poke(false.B)
        dut.io.memory.req.valid.expect(true.B)
        val tag = dut.io.memory.req.bits.tag.peek().litValue
        if (issued) {
          dut.io.memory.req.ready.poke(true.B)
          dut.clock.step()
        }
        dut.io.flush.poke(true.B)
        dut.clock.step()
        dut.io.flush.poke(false.B)
        if (issued) {
          dut.io.memory.resp.valid.poke(true.B)
          dut.io.memory.resp.bits.tag.poke(tag.U)
          dut.io.memory.resp.bits.error.poke(true.B)
          dut.io.memory.resp.bits.fault.poke(EvuFault.Bus)
          dut.io.memory.resp.ready.expect(true.B)
          dut.clock.step()
          dut.io.memory.resp.valid.poke(false.B)
        }
        dut.io.client.resp.valid.expect(true.B)
        dut.io.client.resp.bits.tag.expect(5.U)
        dut.io.client.resp.bits.error.expect(false.B)
        dut.io.client.resp.ready.poke(true.B)
        dut.clock.step(4)
        dut.io.busy.expect(false.B)
        dut.io.start.valid.poke(true.B)
        dut.io.start.bits.poke(1.U)
        dut.clock.step()
        dut.io.start.valid.poke(false.B)
        dut.io.client.req.valid.poke(true.B)
        dut.io.client.req.ready.expect(true.B)
      }
    }
  }

  for (write <- Seq(false, true)) {
    it should s"share two half-line ${if (write) "stores" else "loads"} and acknowledge both owners after memory" in {
      test(new EvuVmuLineCoalescer(EvuParams(enable = true)))
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          dut.io.flush.poke(false.B)
          dut.io.start.valid.poke(true.B)
          dut.io.start.bits.poke(2.U)
          dut.io.end.poke(false.B)
          dut.io.client.req.valid.poke(false.B)
          dut.io.client.resp.ready.poke(false.B)
          dut.io.memory.req.ready.poke(false.B)
          dut.io.memory.resp.valid.poke(false.B)
          dut.io.memory.resp.bits.data.poke(0.U)
          dut.io.memory.resp.bits.tag.poke(0.U)
          dut.io.memory.resp.bits.error.poke(false.B)
          dut.io.memory.resp.bits.fault.poke(EvuFault.None)
          dut.clock.step()
          dut.io.start.valid.poke(false.B)
          val lo = BigInt("12" * 32, 16)
          val hi = BigInt("34" * 32, 16)
          for (half <- 0 until 2) {
            val req = dut.io.client.req
            req.valid.poke(true.B)
            req.bits.addr.poke((128 + half * 32).U)
            req.bits.data.poke((if (half == 0) lo else hi).U)
            req.bits.mask.poke(((BigInt(1) << 32) - 1).U)
            req.bits.bytes.poke(32.U)
            req.bits.write.poke(write.B)
            req.bits.tag.poke((3 + half).U)
            req.ready.expect(true.B)
            dut.clock.step()
          }
          dut.io.client.req.valid.poke(false.B)
          dut.io.end.poke(true.B)
          dut.clock.step()
          dut.io.end.poke(false.B)
          dut.io.memory.req.valid.expect(true.B)
          dut.io.memory.req.bits.addr.expect(128.U)
          dut.io.memory.req.bits.bytes.expect(64.U)
          dut.io.memory.req.bits.write.expect(write.B)
          if (write) {
            dut.io.memory.req.bits.mask.expect(((BigInt(1) << 64) - 1).U)
            dut.io.memory.req.bits.data.expect(((hi << 256) | lo).U)
          }
          val tag = dut.io.memory.req.bits.tag.peek().litValue
          dut.io.client.resp.valid.expect(false.B)
          dut.io.memory.req.ready.poke(true.B)
          dut.clock.step()
          dut.io.memory.req.valid.expect(false.B)
          dut.clock.step(3)
          dut.io.client.resp.valid.expect(false.B)
          dut.io.memory.resp.valid.poke(true.B)
          dut.io.memory.resp.bits.tag.poke(tag.U)
          dut.io.memory.resp.bits.data.poke(((hi << 256) | lo).U)
          dut.io.memory.resp.ready.expect(true.B)
          dut.clock.step()
          dut.io.memory.resp.valid.poke(false.B)
          dut.io.client.resp.ready.poke(true.B)
          for (half <- 0 until 2) {
            dut.io.client.resp.valid.expect(true.B)
            dut.io.client.resp.bits.tag.expect((3 + half).U)
            dut.io.client.resp.bits.error.expect(false.B)
            if (!write) {
              val data = if (half == 0) (hi << 256) | lo else hi
              dut.io.client.resp.bits.data.expect(data.U)
            }
            dut.clock.step()
          }
          dut.clock.step(2)
          dut.io.busy.expect(false.B)
        }
    }
  }

  it should "drain an incomplete store without a later command or end notification" in {
    test(new EvuVmuLineCoalescer(EvuParams(enable = true)))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.flush.poke(false.B)
        dut.io.start.valid.poke(true.B)
        dut.io.start.bits.poke(0.U)
        dut.io.end.poke(false.B)
        dut.io.client.req.valid.poke(false.B)
        dut.io.client.resp.ready.poke(true.B)
        dut.io.memory.req.ready.poke(false.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        dut.clock.step()
        dut.io.start.valid.poke(false.B)
        dut.io.client.req.valid.poke(true.B)
        dut.io.client.req.bits.addr.poke(36.U)
        dut.io.client.req.bits.bytes.poke(4.U)
        dut.io.client.req.bits.mask.poke(15.U)
        dut.io.client.req.bits.data.poke(0x12345678L.U)
        dut.io.client.req.bits.write.poke(true.B)
        dut.io.client.req.bits.tag.poke(6.U)
        dut.clock.step()
        dut.io.client.req.valid.poke(false.B)
        dut.clock.step(33)
        dut.io.memory.req.valid.expect(true.B)
        dut.io.memory.req.bits.mask.expect((BigInt(15) << 36).U)
        dut.io.memory.req.bits.data.expect((BigInt(0x12345678L) << 288).U)
        dut.io.memory.req.bits.addr.expect(0.U)
        val tag = dut.io.memory.req.bits.tag.peek().litValue
        dut.io.memory.req.ready.poke(true.B)
        dut.clock.step()
        dut.io.memory.resp.valid.poke(true.B)
        dut.io.memory.resp.bits.tag.poke(tag.U)
        dut.io.memory.resp.bits.error.poke(true.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.Bus)
        dut.clock.step()
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.client.resp.valid.expect(true.B)
        dut.io.client.resp.bits.tag.expect(6.U)
        dut.io.client.resp.bits.error.expect(true.B)
        dut.io.client.resp.bits.fault.expect(EvuFault.Bus)
        dut.clock.step(3)
        dut.io.busy.expect(false.B)
      }
  }
}
