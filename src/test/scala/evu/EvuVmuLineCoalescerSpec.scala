package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuVmuLineCoalescerSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Bounded multi-command VMU line coalescing"

  it should "serialize overlapping and cross-epoch stores including a crossing request's second line" in {
    test(new EvuVmuLineCoalescer(EvuParams(enable = true)))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.flush.poke(false.B)
        dut.io.drain.poke(false.B)
        dut.io.storePending.poke(true.B)
        dut.io.client.req.valid.poke(false.B)
        dut.io.client.resp.ready.poke(true.B)
        dut.io.memory.req.ready.poke(false.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        def request(tag: Int, addr: Int, bytes: Int, epoch: Int = 0): Unit = {
          val r = dut.io.client.req
          r.valid.poke(true.B)
          r.bits.jobId.poke(tag.U)
          r.bits.epoch.poke(epoch.U)
          r.bits.tag.poke(tag.U)
          r.bits.addr.poke(addr.U)
          r.bits.bytes.poke(bytes.U)
          r.bits.mask.poke(((BigInt(1) << bytes) - 1).U)
          r.bits.data.poke(1.U)
          r.bits.write.poke(true.B)
        }
        def retire(): Unit = {
          dut.io.client.req.valid.poke(false.B)
          dut.io.drain.poke(true.B)
          dut.io.memory.req.valid.expect(true.B)
          val tag = dut.io.memory.req.bits.tag.peek().litValue
          dut.io.memory.req.ready.poke(true.B)
          dut.clock.step()
          dut.io.memory.req.ready.poke(false.B)
          dut.io.drain.poke(false.B)
          dut.io.storePending.poke(true.B)
          dut.io.memory.resp.bits.tag.poke(tag.U)
          dut.io.memory.resp.valid.poke(true.B)
          dut.io.memory.resp.ready.expect(true.B)
          dut.clock.step()
          dut.io.memory.resp.valid.poke(false.B)
          dut.io.client.resp.valid.expect(true.B)
          dut.clock.step()
          dut.io.busy.expect(false.B)
        }
        request(0, 128, 4)
        dut.io.client.req.ready.expect(true.B)
        dut.clock.step()
        request(1, 128, 4) // Even equal values need separate physical observations.
        dut.io.client.req.ready.expect(false.B)
        dut.io.storeMerge.expect(false.B)
        request(1, 132, 4, epoch = 1)
        dut.io.client.req.ready.expect(false.B)
        retire()

        request(0, 188, 8) // Bytes 188..195 span lines 128 and 192.
        dut.io.client.req.ready.expect(true.B)
        dut.clock.step()
        request(1, 192, 4)
        dut.io.client.req.ready.expect(false.B)
        dut.io.storeMerge.expect(false.B)
        retire()
        request(1, 192, 4)
        dut.io.client.req.ready.expect(true.B)
      }
  }

  for (issued <- Seq(false, true)) {
    it should s"cancel a ${if (issued) "sent" else "buffered"} owner on flush before reusing its tag" in {
      test(new EvuVmuLineCoalescer(EvuParams(enable = true))).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.flush.poke(false.B)
        dut.io.drain.poke(false.B)
        dut.io.storePending.poke(true.B)
        dut.io.client.req.valid.poke(false.B)
        dut.io.client.resp.ready.poke(false.B)
        dut.io.memory.req.ready.poke(false.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        dut.clock.step()
        dut.io.client.req.valid.poke(true.B)
        dut.io.client.req.bits.addr.poke(128.U)
        dut.io.client.req.bits.data.poke(0.U)
        dut.io.client.req.bits.mask.poke(0xffffffffL.U)
        dut.io.client.req.bits.bytes.poke(32.U)
        dut.io.client.req.bits.write.poke(false.B)
        dut.io.client.req.bits.tag.poke(5.U)
        dut.io.client.req.bits.jobId.poke(0.U)
        dut.io.client.req.bits.epoch.poke(0.U)
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
        dut.clock.step()
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
          dut.io.drain.poke(false.B)
          dut.io.storePending.poke(true.B)
          dut.io.client.req.valid.poke(false.B)
          dut.io.client.resp.ready.poke(false.B)
          dut.io.memory.req.ready.poke(false.B)
          dut.io.memory.resp.valid.poke(false.B)
          dut.io.memory.resp.bits.data.poke(0.U)
          dut.io.memory.resp.bits.tag.poke(0.U)
          dut.io.memory.resp.bits.error.poke(false.B)
          dut.io.memory.resp.bits.fault.poke(EvuFault.None)
          dut.clock.step()
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
            req.bits.jobId.poke(2.U)
            req.bits.epoch.poke(0.U)
            req.ready.expect(true.B)
            dut.clock.step()
          }
          dut.io.client.req.valid.poke(false.B)
          dut.clock.step()
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

  it should "bound collection when a pending store stops making progress" in {
    test(new EvuVmuLineCoalescer(EvuParams(enable = true)))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.flush.poke(false.B)
        dut.io.drain.poke(false.B)
        dut.io.storePending.poke(true.B)
        dut.io.client.req.valid.poke(false.B)
        dut.io.client.resp.ready.poke(true.B)
        dut.io.memory.req.ready.poke(false.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        dut.clock.step()
        dut.io.client.req.valid.poke(true.B)
        dut.io.client.req.bits.addr.poke(36.U)
        dut.io.client.req.bits.bytes.poke(4.U)
        dut.io.client.req.bits.mask.poke(15.U)
        dut.io.client.req.bits.data.poke(0x12345678L.U)
        dut.io.client.req.bits.write.poke(true.B)
        dut.io.client.req.bits.tag.poke(6.U)
        dut.io.client.req.bits.jobId.poke(0.U)
        dut.io.client.req.bits.epoch.poke(0.U)
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

  for (error <- Seq(false, true)) {
    it should s"combine disjoint jobs and fan out ${if (error) "faults" else "Acks"} only after a physical response" in {
      test(new EvuVmuLineCoalescer(EvuParams(enable = true)))
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          dut.io.flush.poke(false.B)
          dut.io.drain.poke(false.B)
          dut.io.storePending.poke(true.B)
          dut.io.client.req.valid.poke(false.B)
          dut.io.client.resp.ready.poke(false.B)
          dut.io.memory.req.ready.poke(false.B)
          dut.io.memory.resp.valid.poke(false.B)
          dut.io.memory.resp.bits.data.poke(0.U)
          dut.io.memory.resp.bits.tag.poke(0.U)
          dut.io.memory.resp.bits.error.poke(error.B)
          dut.io.memory.resp.bits.fault.poke(if (error) EvuFault.Bus else EvuFault.None)
          def request(job: Int, tag: Int, offset: Int, write: Boolean): Unit = {
            val r = dut.io.client.req
            r.valid.poke(true.B)
            r.bits.jobId.poke(job.U)
            r.bits.epoch.poke(1.U)
            r.bits.tag.poke(tag.U)
            r.bits.addr.poke((128 + offset).U)
            r.bits.bytes.poke(4.U)
            r.bits.mask.poke(15.U)
            r.bits.data.poke((job + 1).U)
            r.bits.write.poke(write.B)
          }
          request(0, 0, 0, write = true)
          dut.io.client.req.ready.expect(true.B)
          dut.clock.step()
          request(1, 1, 4, write = true)
          dut.io.storeMerge.expect(true.B)
          dut.clock.step()
          dut.io.client.req.valid.poke(false.B)
          dut.io.client.resp.valid.expect(false.B)
          dut.io.drain.poke(true.B)
          dut.io.memory.req.valid.expect(true.B)
          dut.io.memory.req.bits.mask.expect(255.U)
          dut.io.memory.req.bits.data.expect(((BigInt(2) << 32) | 1).U)
          dut.io.memory.req.ready.poke(true.B)
          val tag = dut.io.memory.req.bits.tag.peek().litValue
          dut.clock.step()
          dut.io.memory.req.ready.poke(false.B)
          dut.io.drain.poke(false.B)
          dut.io.storePending.poke(true.B)
          request(2, 2, 0, write = false)
          dut.io.client.req.ready.expect(false.B)
          dut.io.client.resp.valid.expect(false.B)
          dut.io.client.req.valid.poke(false.B)
          dut.io.memory.resp.valid.poke(true.B)
          dut.io.memory.resp.bits.tag.poke(tag.U)
          dut.clock.step()
          dut.io.memory.resp.valid.poke(false.B)
          dut.io.client.resp.ready.poke(true.B)
          for (owner <- 0 until 2) {
            dut.io.client.resp.valid.expect(true.B)
            dut.io.client.resp.bits.tag.expect(owner.U)
            dut.io.client.resp.bits.error.expect(error.B)
            dut.clock.step()
          }
          dut.io.busy.expect(false.B)
          request(2, 2, 0, write = false)
          dut.io.client.req.ready.expect(true.B)
          dut.io.loadMerge.expect(false.B) // No stale cross-job read caching.
        }
    }
  }
  for (depth <- Seq(1, 2, 4)) {
    it should s"send an isolated partial store immediately at command depth $depth" in {
      test(new EvuVmuLineCoalescer(EvuParams(enable = true, memoryCommandDepth = depth)))
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          dut.io.flush.poke(false.B)
          dut.io.drain.poke(false.B)
          dut.io.storePending.poke(false.B)
          dut.io.client.req.valid.poke(true.B)
          dut.io.client.req.bits.addr.poke(132.U)
          dut.io.client.req.bits.bytes.poke(4.U)
          dut.io.client.req.bits.mask.poke(15.U)
          dut.io.client.req.bits.data.poke(7.U)
          dut.io.client.req.bits.write.poke(true.B)
          dut.io.client.req.bits.tag.poke(0.U)
          dut.io.client.req.bits.jobId.poke(0.U)
          dut.io.client.req.bits.epoch.poke(0.U)
          dut.io.client.resp.ready.poke(true.B)
          dut.io.memory.req.ready.poke(false.B)
          dut.io.memory.resp.valid.poke(false.B)
          dut.io.client.req.ready.expect(true.B)
          dut.clock.step()
          dut.io.client.req.valid.poke(false.B)
          dut.io.client.req.bits.addr.poke(256.U)
          dut.io.memory.req.valid.expect(true.B)
          dut.io.memory.req.bits.mask.expect(240.U)
          // A real successor may justify a short collection interval.
          dut.io.storePending.poke(true.B)
          dut.io.memory.req.valid.expect(false.B)
          dut.clock.step(3)
          dut.io.memory.req.valid.expect(false.B)
          dut.io.storePending.poke(false.B)
          dut.io.memory.req.valid.expect(true.B)
          // Even with another store in flight, an incompatible request seals it.
          dut.io.storePending.poke(true.B)
          dut.io.client.req.valid.poke(true.B)
          dut.io.client.req.bits.addr.poke(256.U)
          dut.io.client.req.bits.tag.poke(1.U)
          dut.io.client.req.bits.jobId.poke(1.U)
          dut.io.memory.req.valid.expect(true.B)
        }
    }
  }

  it should "avoid collection bubbles in 96 sequential scalar state stores without successors" in {
    test(new EvuVmuLineCoalescer(EvuParams(enable = true, memoryCommandDepth = 2)))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.flush.poke(false.B)
        dut.io.drain.poke(false.B)
        dut.io.storePending.poke(false.B)
        dut.io.client.req.valid.poke(false.B)
        dut.io.client.resp.ready.poke(true.B)
        dut.io.memory.req.ready.poke(true.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        var collectionCycles = 0
        for (state <- 0 until 96) {
          val request = dut.io.client.req
          request.valid.poke(true.B)
          request.bits.addr.poke((128 + state * 4).U)
          request.bits.data.poke(state.U)
          request.bits.mask.poke(15.U)
          request.bits.bytes.poke(4.U)
          request.bits.write.poke(true.B)
          request.bits.jobId.poke((state % 16).U)
          request.bits.tag.poke((state % 8).U)
          request.bits.epoch.poke(0.U)
          request.ready.expect(true.B)
          dut.clock.step()
          request.valid.poke(false.B)
          // An invalid bus may contain any address; it cannot justify waiting.
          request.bits.addr.poke(4096.U)
          var waited = 0
          while (!dut.io.memory.req.valid.peek().litToBoolean && waited < 40) {
            waited += 1
            dut.clock.step()
          }
          waited shouldBe 0
          collectionCycles += waited
          dut.io.memory.req.valid.expect(true.B)
          val tag = dut.io.memory.req.bits.tag.peek().litValue
          dut.clock.step()
          dut.io.memory.resp.valid.poke(true.B)
          dut.io.memory.resp.bits.tag.poke(tag.U)
          dut.io.memory.resp.ready.expect(true.B)
          dut.clock.step()
          dut.io.memory.resp.valid.poke(false.B)
          dut.io.client.resp.valid.expect(true.B)
          dut.io.client.resp.bits.tag.expect((state % 8).U)
          dut.clock.step()
          dut.io.busy.expect(false.B)
        }
        collectionCycles shouldBe 0
      }
  }
}
