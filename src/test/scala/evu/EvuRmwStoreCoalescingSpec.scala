package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuRmwStoreHarness(ep: EvuParams) extends Module {
  val io = IO(new Bundle {
    val client = Flipped(new EvuLineMemoryIO(ep))
    val memory = new EvuLineMemoryIO(ep)
    val flush = Input(Bool())
    val drain = Input(Bool())
    val busy = Output(Bool())
    val baseReturning = Output(Bool())
    val merged = Output(Bool())
  })
  val lines = Module(new EvuVmuLineCoalescer(ep))
  val tags = Module(new EvuMemoryTagRouter(ep))
  val adapter = Module(new EvuHbl2LineAdapter(ep))
  lines.io.client <> io.client
  tags.io.clients(0).req.valid := false.B
  tags.io.clients(0).req.bits := 0.U.asTypeOf(tags.io.clients(0).req.bits)
  tags.io.clients(0).resp.ready := true.B
  tags.io.clients(1) <> lines.io.memory
  adapter.io.client <> tags.io.memory
  io.memory <> adapter.io.memory
  lines.io.flush := io.flush
  lines.io.drain := io.drain
  lines.io.storePending := false.B
  tags.io.flush := io.flush
  tags.io.adapterBusy := adapter.io.busy
  tags.io.lookupTag := 0.U
  adapter.io.flush := io.flush
  io.busy := lines.io.busy || tags.io.busy || adapter.io.busy
  io.baseReturning := lines.io.memory.resp.fire
  io.merged := lines.io.rmwMerge
}

class EvuRmwStoreCoalescingSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Store merging during an existing line Get"

  for (error <- Seq(0, 1, 2)) {
    it should s"merge three owners into one Get/Put and wait for the final Ack (error stage $error)" in {
      test(new EvuRmwStoreHarness(EvuParams(enable = true)))
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          dut.io.flush.poke(false.B)
          dut.io.drain.poke(false.B)
          dut.io.client.req.valid.poke(false.B)
          dut.io.client.resp.ready.poke(false.B)
          dut.io.memory.req.ready.poke(false.B)
          dut.io.memory.resp.valid.poke(false.B)
          dut.io.memory.resp.bits.data.poke(0.U)
          dut.io.memory.resp.bits.error.poke(false.B)
          dut.io.memory.resp.bits.fault.poke(EvuFault.None)
          dut.io.memory.resp.bits.tag.poke(0.U)
          def await(signal: Bool): Unit = {
            var cycles = 0
            while (!signal.peek().litToBoolean && cycles < 80) { dut.clock.step(); cycles += 1 }
            signal.expect(true.B)
          }
          def store(owner: Int, accept: Boolean = true): Unit = {
            val r = dut.io.client.req
            r.valid.poke(true.B)
            r.bits.jobId.poke(owner.U)
            r.bits.epoch.poke(0.U)
            r.bits.tag.poke(owner.U)
            r.bits.addr.poke((128 + owner * 4).U)
            r.bits.bytes.poke(4.U)
            r.bits.mask.poke(15.U)
            r.bits.data.poke((0x12340000L + owner).U)
            r.bits.fullLineData.poke(false.B)
            r.bits.write.poke(true.B)
            await(r.ready)
            if (accept) { dut.clock.step(); r.valid.poke(false.B) }
          }
          store(0)
          await(dut.io.memory.req.valid)
          dut.io.memory.req.bits.addr.expect(128.U)
          dut.io.memory.req.bits.write.expect(false.B)
          val getTag = dut.io.memory.req.bits.tag.peek().litValue
          dut.io.memory.req.ready.poke(true.B)
          dut.clock.step()
          dut.io.memory.req.ready.poke(false.B)
          dut.clock.step(4)
          store(1)
          dut.io.client.resp.valid.expect(false.B)
          val old = BigInt("aa" * 64, 16)
          dut.io.memory.resp.bits.tag.poke(getTag.U)
          dut.io.memory.resp.bits.data.poke(old.U)
          dut.io.memory.resp.bits.error.poke((error == 1).B)
          dut.io.memory.resp.bits.fault.poke(if (error == 1) EvuFault.Bus else EvuFault.None)
          dut.io.memory.resp.valid.poke(true.B)
          await(dut.io.memory.resp.ready)
          dut.clock.step()
          dut.io.memory.resp.valid.poke(false.B)
          // Join exactly when the coalescer receives its base line.
          await(dut.io.baseReturning)
          store(2, accept = false)
          dut.io.merged.expect(true.B)
          dut.clock.step()
          dut.io.client.req.valid.poke(false.B)
          if (error != 1) {
            await(dut.io.memory.req.valid)
            dut.io.memory.req.bits.write.expect(true.B) // Never a second Get.
            dut.io.memory.req.bits.mask.expect(4095.U)
            val enabled = (BigInt(1) << 96) - 1
            val payload = (0 until 3).map(i => BigInt(0x12340000L + i) << (32 * i)).reduce(_ | _)
            dut.io.memory.req.bits.data.expect(((old & ~enabled) | payload).U)
            val putTag = dut.io.memory.req.bits.tag.peek().litValue
            dut.io.client.resp.valid.expect(false.B)
            dut.io.memory.req.ready.poke(true.B)
            dut.clock.step()
            dut.io.memory.req.ready.poke(false.B)
            dut.clock.step(5)
            dut.io.client.resp.valid.expect(false.B)
            dut.io.memory.resp.bits.tag.poke(putTag.U)
            dut.io.memory.resp.bits.error.poke((error == 2).B)
            dut.io.memory.resp.bits.fault.poke(if (error == 2) EvuFault.Bus else EvuFault.None)
            dut.io.memory.resp.valid.poke(true.B)
            await(dut.io.memory.resp.ready)
            dut.clock.step()
            dut.io.memory.resp.valid.poke(false.B)
          }
          await(dut.io.client.resp.valid)
          dut.io.client.resp.ready.poke(true.B)
          for (owner <- 0 until 3) {
            dut.io.client.resp.valid.expect(true.B)
            dut.io.client.resp.bits.tag.expect(owner.U)
            dut.io.client.resp.bits.error.expect((error != 0).B)
            dut.clock.step()
          }
          dut.clock.step(3)
          dut.io.memory.req.valid.expect(false.B)
          dut.io.busy.expect(false.B)
        }
    }
  }

  for (stage <- Seq("buffered", "get_wait", "prepared", "put_wait")) {
    it should s"drain a flushed $stage store before releasing its owner and tag" in {
      test(new EvuRmwStoreHarness(EvuParams(enable = true)))
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          dut.io.flush.poke(false.B)
          dut.io.drain.poke(false.B)
          dut.io.client.req.valid.poke(false.B)
          dut.io.client.resp.ready.poke(false.B)
          dut.io.memory.req.ready.poke(false.B)
          dut.io.memory.resp.valid.poke(false.B)
          dut.io.memory.resp.bits.data.poke(0.U)
          dut.io.memory.resp.bits.error.poke(false.B)
          dut.io.memory.resp.bits.fault.poke(EvuFault.None)
          dut.io.memory.resp.bits.tag.poke(0.U)
          def await(signal: Bool): Unit = {
            var cycles = 0
            while (!signal.peek().litToBoolean && cycles < 80) { dut.clock.step(); cycles += 1 }
            signal.expect(true.B)
          }
          def acceptMemory(write: Boolean): BigInt = {
            await(dut.io.memory.req.valid)
            dut.io.memory.req.bits.write.expect(write.B)
            val tag = dut.io.memory.req.bits.tag.peek().litValue
            dut.io.memory.req.ready.poke(true.B)
            dut.clock.step()
            dut.io.memory.req.ready.poke(false.B)
            tag
          }
          def respond(tag: BigInt): Unit = {
            dut.io.memory.resp.bits.tag.poke(tag.U)
            dut.io.memory.resp.bits.data.poke(BigInt("aa" * 64, 16).U)
            dut.io.memory.resp.valid.poke(true.B)
            await(dut.io.memory.resp.ready)
            dut.clock.step()
            dut.io.memory.resp.valid.poke(false.B)
          }
          val req = dut.io.client.req
          req.valid.poke(true.B)
          req.bits.jobId.poke(1.U)
          req.bits.epoch.poke(0.U)
          req.bits.tag.poke(5.U)
          req.bits.addr.poke(132.U)
          req.bits.bytes.poke(4.U)
          req.bits.mask.poke(15.U)
          req.bits.data.poke(0x12345678L.U)
          req.bits.fullLineData.poke(false.B)
          req.bits.write.poke(true.B)
          await(req.ready)
          dut.clock.step()
          req.valid.poke(false.B)

          var outstanding = Option.empty[BigInt]
          if (stage != "buffered") {
            val get = acceptMemory(write = false)
            if (stage == "get_wait") { outstanding = Some(get) }
            else {
              respond(get)
              await(dut.io.baseReturning)
              dut.clock.step()
              if (stage == "put_wait") { outstanding = Some(acceptMemory(write = true)) }
            }
          }
          dut.io.flush.poke(true.B)
          dut.clock.step()
          dut.io.flush.poke(false.B)
          dut.io.memory.req.valid.expect(false.B)
          outstanding.foreach { tag =>
            dut.clock.step(4)
            dut.io.client.resp.valid.expect(false.B)
            dut.io.busy.expect(true.B)
            respond(tag)
          }
          await(dut.io.client.resp.valid)
          dut.io.client.resp.bits.tag.expect(5.U)
          dut.io.client.resp.bits.error.expect(false.B)
          dut.io.client.resp.ready.poke(true.B)
          dut.clock.step(5)
          dut.io.busy.expect(false.B)
          dut.io.memory.req.valid.expect(false.B)
          req.bits.epoch.poke(1.U)
          req.valid.poke(true.B)
          req.ready.expect(true.B)
        }
    }
  }

  for (conflict <- Seq("overlapping_store", "different_epoch", "dependent_load")) {
    it should s"keep a $conflict ordered through the RMW Get and final Put Ack" in {
      test(new EvuVmuLineCoalescer(EvuParams(enable = true)))
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          dut.io.flush.poke(false.B)
          dut.io.drain.poke(false.B)
          dut.io.storePending.poke(false.B)
          dut.io.client.req.valid.poke(false.B)
          dut.io.client.resp.ready.poke(true.B)
          dut.io.memory.req.ready.poke(false.B)
          dut.io.memory.resp.valid.poke(false.B)
          dut.io.memory.resp.bits.data.poke(0.U)
          dut.io.memory.resp.bits.error.poke(false.B)
          dut.io.memory.resp.bits.fault.poke(EvuFault.None)
          dut.io.memory.resp.bits.tag.poke(0.U)
          val req = dut.io.client.req
          req.valid.poke(true.B)
          req.bits.jobId.poke(0.U)
          req.bits.epoch.poke(0.U)
          req.bits.tag.poke(0.U)
          req.bits.addr.poke(128.U)
          req.bits.bytes.poke(4.U)
          req.bits.mask.poke(15.U)
          req.bits.data.poke(0x12345678L.U)
          req.bits.fullLineData.poke(false.B)
          req.bits.write.poke(true.B)
          req.ready.expect(true.B)
          dut.clock.step()
          req.valid.poke(false.B)
          dut.io.memory.req.valid.expect(true.B)
          dut.io.memory.req.bits.write.expect(false.B)
          val tag = dut.io.memory.req.bits.tag.peek().litValue
          dut.io.memory.req.ready.poke(true.B)
          dut.clock.step()
          dut.io.memory.req.ready.poke(false.B)

          req.valid.poke(true.B)
          req.bits.jobId.poke(1.U)
          req.bits.tag.poke(1.U)
          req.bits.addr.poke((if (conflict == "overlapping_store") 128 else 132).U)
          req.bits.epoch.poke((if (conflict == "different_epoch") 1 else 0).U)
          req.bits.write.poke((conflict != "dependent_load").B)
          req.ready.expect(false.B)
          dut.io.storeMerge.expect(false.B)
          dut.clock.step(3)
          req.ready.expect(false.B)
          dut.io.memory.resp.bits.tag.poke(tag.U)
          dut.io.memory.resp.valid.poke(true.B)
          dut.clock.step()
          dut.io.memory.resp.valid.poke(false.B)
          req.ready.expect(false.B)
          dut.io.memory.req.valid.expect(true.B)
          dut.io.memory.req.bits.write.expect(true.B)
          dut.io.memory.req.bits.fullLineData.expect(true.B)
          dut.io.memory.req.ready.poke(true.B)
          dut.clock.step()
          dut.io.memory.req.ready.poke(false.B)
          req.ready.expect(false.B)
          dut.io.client.resp.valid.expect(false.B)
          dut.io.memory.resp.valid.poke(true.B)
          dut.clock.step()
          dut.io.memory.resp.valid.poke(false.B)
          req.ready.expect(false.B)
          dut.io.client.resp.valid.expect(true.B)
          dut.clock.step()
          req.ready.expect(true.B)
        }
    }
  }
}
