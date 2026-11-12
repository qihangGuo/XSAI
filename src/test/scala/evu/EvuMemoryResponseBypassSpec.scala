package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuMemoryResponseBypassSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "VMU final-response forwarding"

  for (write <- Seq(false, true); blocked <- Seq(false, true); error <- Seq(false, true)) {
    it should s"forward a single-line response through both stages write=$write blocked=$blocked error=$error" in {
      test(new EvuRmwStoreHarness(EvuParams(enable = true)))
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          dut.io.flush.poke(false.B)
          dut.io.drain.poke(false.B)
          dut.io.client.req.valid.poke(false.B)
          dut.io.client.resp.ready.poke((!blocked).B)
          dut.io.memory.req.ready.poke(false.B)
          dut.io.memory.resp.valid.poke(false.B)
          dut.io.memory.resp.bits.data.poke(0.U)
          dut.io.memory.resp.bits.error.poke(false.B)
          dut.io.memory.resp.bits.fault.poke(EvuFault.None)
          dut.io.memory.resp.bits.tag.poke(0.U)
          def await(signal: Bool): Unit = {
            var cycles = 0
            while (!signal.peek().litToBoolean && cycles < 40) { dut.clock.step(); cycles += 1 }
            signal.expect(true.B)
          }
          val r = dut.io.client.req
          r.bits.jobId.poke(3.U)
          r.bits.epoch.poke(1.U)
          r.bits.addr.poke((if (write) 128 else 140).U)
          r.bits.data.poke(BigInt("ab" * 64, 16).U)
          r.bits.mask.poke((if (write) (BigInt(1) << 64) - 1 else BigInt(15)).U)
          r.bits.fullLineData.poke(false.B)
          r.bits.bytes.poke((if (write) 64 else 4).U)
          r.bits.write.poke(write.B)
          r.bits.tag.poke(5.U)
          r.valid.poke(true.B)
          r.ready.expect(true.B)
          dut.clock.step()
          r.valid.poke(false.B)
          await(dut.io.memory.req.valid)
          dut.io.memory.req.bits.addr.expect(128.U)
          dut.io.memory.req.bits.write.expect(write.B)
          val tag = dut.io.memory.req.bits.tag.peek().litValue
          dut.io.memory.req.ready.poke(true.B)
          dut.clock.step()
          dut.io.memory.req.ready.poke(false.B)
          dut.clock.step(3)
          dut.io.client.resp.valid.expect(false.B)
          dut.io.busy.expect(true.B)
          val data = BigInt("12345678" * 16, 16)
          dut.io.memory.resp.bits.data.poke(data.U)
          dut.io.memory.resp.bits.tag.poke(tag.U)
          dut.io.memory.resp.bits.error.poke(error.B)
          dut.io.memory.resp.bits.fault.poke(if (error) EvuFault.Access else EvuFault.None)
          dut.io.memory.resp.valid.poke(true.B)
          dut.io.memory.resp.ready.expect(true.B)
          // No clock edge between physical D and the logical owner's response.
          dut.io.client.resp.valid.expect(true.B)
          dut.io.client.resp.bits.tag.expect(5.U)
          dut.io.client.resp.bits.error.expect(error.B)
          dut.io.client.resp.bits.fault.expect(if (error) EvuFault.Access else EvuFault.None)
          if (!write && !error) { dut.io.client.resp.bits.data.expect((data >> 96).U) }
          dut.clock.step()
          dut.io.memory.resp.valid.poke(false.B)
          if (blocked) {
            dut.io.client.resp.valid.expect(true.B)
            dut.io.client.resp.bits.tag.expect(5.U)
            dut.io.client.resp.bits.error.expect(error.B)
            dut.io.client.resp.bits.fault.expect(if (error) EvuFault.Access else EvuFault.None)
            if (!write && !error) { dut.io.client.resp.bits.data.expect((data >> 96).U) }
            dut.clock.step(4)
            dut.io.busy.expect(true.B)
            dut.io.client.resp.ready.poke(true.B)
            dut.clock.step()
          }
          dut.io.client.resp.valid.expect(false.B)
          dut.io.busy.expect(false.B)
          dut.clock.step(3)
          dut.io.client.resp.valid.expect(false.B)
        }
    }
  }

  it should "keep an RMW owner live after Get and forward only its final Put Ack" in {
    test(new EvuRmwStoreHarness(EvuParams(enable = true)))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.flush.poke(false.B)
        dut.io.drain.poke(true.B)
        dut.io.client.req.valid.poke(true.B)
        dut.io.client.resp.ready.poke(true.B)
        dut.io.memory.req.ready.poke(false.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.data.poke(0.U)
        val r = dut.io.client.req.bits
        r.jobId.poke(2.U)
        r.epoch.poke(0.U)
        r.addr.poke(132.U)
        r.data.poke(0x12345678L.U)
        r.mask.poke(15.U)
        r.fullLineData.poke(false.B)
        r.bytes.poke(4.U)
        r.write.poke(true.B)
        r.tag.poke(6.U)
        dut.io.client.req.ready.expect(true.B)
        dut.clock.step()
        dut.io.client.req.valid.poke(false.B)
        def busRequest(write: Boolean): BigInt = {
          var cycles = 0
          while (!dut.io.memory.req.valid.peek().litToBoolean && cycles < 40) {
            dut.clock.step(); cycles += 1
          }
          dut.io.memory.req.valid.expect(true.B)
          dut.io.memory.req.bits.write.expect(write.B)
          val tag = dut.io.memory.req.bits.tag.peek().litValue
          dut.io.memory.req.ready.poke(true.B)
          dut.clock.step()
          dut.io.memory.req.ready.poke(false.B)
          tag
        }
        val getTag = busRequest(write = false)
        val old = BigInt("aa" * 64, 16)
        dut.io.memory.resp.bits.tag.poke(getTag.U)
        dut.io.memory.resp.bits.data.poke(old.U)
        dut.io.memory.resp.valid.poke(true.B)
        dut.io.memory.resp.ready.expect(true.B)
        dut.io.baseReturning.expect(true.B)
        dut.io.client.resp.valid.expect(false.B)
        dut.clock.step()
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.client.resp.valid.expect(false.B)
        val putTag = busRequest(write = true)
        dut.clock.step(5)
        dut.io.busy.expect(true.B)
        dut.io.client.resp.valid.expect(false.B)
        dut.io.memory.resp.bits.tag.poke(putTag.U)
        dut.io.memory.resp.valid.poke(true.B)
        dut.io.memory.resp.ready.expect(true.B)
        dut.io.client.resp.valid.expect(true.B)
        dut.io.client.resp.bits.tag.expect(6.U)
        dut.clock.step()
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.client.resp.valid.expect(false.B)
        dut.io.busy.expect(false.B)
      }
  }

  it should "route reordered responses once while buffered owners and new returns compete" in {
    test(new EvuRmwStoreHarness(EvuParams(enable = true)))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.flush.poke(false.B)
        dut.io.drain.poke(false.B)
        dut.io.client.req.valid.poke(false.B)
        dut.io.client.resp.ready.poke(false.B)
        dut.io.memory.req.ready.poke(false.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.data.poke(0.U)
        val physicalTags = (0 until 6).map { owner =>
          val r = dut.io.client.req
          r.valid.poke(true.B)
          r.bits.jobId.poke(owner.U)
          r.bits.epoch.poke(0.U)
          r.bits.addr.poke((128 + owner * 64).U)
          r.bits.data.poke(owner.U)
          r.bits.mask.poke(((BigInt(1) << 64) - 1).U)
          r.bits.fullLineData.poke(false.B)
          r.bits.bytes.poke(64.U)
          r.bits.write.poke((owner % 2 == 1).B)
          r.bits.tag.poke(owner.U)
          r.ready.expect(true.B)
          dut.clock.step()
          r.valid.poke(false.B)
          var cycles = 0
          while (!dut.io.memory.req.valid.peek().litToBoolean && cycles < 40) {
            dut.clock.step(); cycles += 1
          }
          dut.io.memory.req.valid.expect(true.B)
          val tag = dut.io.memory.req.bits.tag.peek().litValue
          dut.io.memory.req.ready.poke(true.B)
          dut.clock.step()
          dut.io.memory.req.ready.poke(false.B)
          tag
        }
        val returned = scala.collection.mutable.Set.empty[Int]
        def tick(): Unit = {
          if (dut.io.client.resp.valid.peek().litToBoolean && dut.io.client.resp.ready.peek().litToBoolean) {
            val owner = dut.io.client.resp.bits.tag.peek().litValue.toInt
            owner should be < 6
            returned.contains(owner) shouldBe false
            val error = owner == 3
            dut.io.client.resp.bits.error.expect(error.B)
            dut.io.client.resp.bits.fault.expect(if (error) EvuFault.Bus else EvuFault.None)
            if (owner % 2 == 0) { dut.io.client.resp.bits.data.expect((0x1000 + owner).U) }
            returned += owner
          }
          dut.clock.step()
        }
        for ((owner, step) <- Seq(4, 1, 5, 0, 3, 2).zipWithIndex) {
          dut.io.client.resp.ready.poke((step >= 2 && step % 2 == 0).B)
          dut.io.memory.resp.valid.poke(true.B)
          dut.io.memory.resp.bits.tag.poke(physicalTags(owner).U)
          dut.io.memory.resp.bits.data.poke((0x1000 + owner).U)
          dut.io.memory.resp.bits.error.poke((owner == 3).B)
          dut.io.memory.resp.bits.fault.poke(if (owner == 3) EvuFault.Bus else EvuFault.None)
          dut.io.memory.resp.ready.expect(true.B)
          tick()
        }
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.client.resp.ready.poke(true.B)
        for (_ <- 0 until 10) { tick() }
        returned.toSet shouldBe (0 until 6).toSet
        dut.io.client.resp.valid.expect(false.B)
        dut.io.busy.expect(false.B)
      }
  }

  it should "buffer both owners when a same-job load joins on the response cycle" in {
    test(new EvuVmuLineCoalescer(EvuParams(enable = true)))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.flush.poke(false.B)
        dut.io.drain.poke(false.B)
        dut.io.storePending.poke(false.B)
        dut.io.client.resp.ready.poke(true.B)
        dut.io.memory.req.ready.poke(true.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        dut.io.client.req.valid.poke(true.B)
        val r = dut.io.client.req.bits
        r.jobId.poke(3.U)
        r.epoch.poke(0.U)
        r.addr.poke(128.U)
        r.data.poke(0.U)
        r.mask.poke(15.U)
        r.fullLineData.poke(false.B)
        r.bytes.poke(4.U)
        r.write.poke(false.B)
        r.tag.poke(2.U)
        dut.io.client.req.ready.expect(true.B)
        dut.clock.step()
        dut.io.client.req.valid.poke(false.B)
        dut.io.memory.req.valid.expect(true.B)
        val tag = dut.io.memory.req.bits.tag.peek().litValue
        dut.clock.step()
        r.addr.poke(132.U)
        r.tag.poke(4.U)
        dut.io.client.req.valid.poke(true.B)
        dut.io.client.req.ready.expect(true.B)
        dut.io.loadMerge.expect(true.B)
        val data = BigInt("1122334455667788", 16)
        dut.io.memory.resp.bits.tag.poke(tag.U)
        dut.io.memory.resp.bits.data.poke(data.U)
        dut.io.memory.resp.valid.poke(true.B)
        dut.io.memory.resp.ready.expect(true.B)
        dut.io.client.resp.valid.expect(false.B)
        dut.clock.step()
        dut.io.client.req.valid.poke(false.B)
        dut.io.memory.resp.valid.poke(false.B)
        for ((owner, shift) <- Seq((2, 0), (4, 32))) {
          dut.io.client.resp.valid.expect(true.B)
          dut.io.client.resp.bits.tag.expect(owner.U)
          dut.io.client.resp.bits.data.expect((data >> shift).U)
          dut.clock.step()
        }
        dut.io.client.resp.valid.expect(false.B)
        dut.io.busy.expect(false.B)
      }
  }
}
