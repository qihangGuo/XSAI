package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import _root_.circt.stage.ChiselStage
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

import scala.collection.mutable

class EvuMatrixWritebackHarness(p: EvuParams) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new EvuMatrixStoreBeat(p)))
    val vector = Flipped(new EvuLineMemoryIO(p))
    val memory = new EvuLineMemoryIO(p)
    val start = Input(Bool())
    val flush = Input(Bool())
    val done = Output(Bool())
    val busy = Output(Bool())
    val error = Output(Bool())
  })
  val stores = Module(new EvuMatrixStoreBuffer(p))
  val tags = Module(new EvuMemoryTagRouter(p))
  val lines = Module(new EvuHbl2LineAdapter(p))
  stores.io.in <> io.in
  stores.io.start := io.start
  stores.io.flush := io.flush
  tags.io.clients(0) <> stores.io.memory
  tags.io.clients(1) <> io.vector
  tags.io.flush := io.flush
  tags.io.adapterBusy := lines.io.busy
  tags.io.lookupTag := 0.U
  lines.io.client <> tags.io.memory
  lines.io.flush := io.flush
  io.memory <> lines.io.memory
  io.done := stores.io.done
  io.busy := stores.io.busy || tags.io.busy || lines.io.busy
  io.error := stores.io.fault.valid
}

class EvuMatrixWritebackSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Matrix writeback through the tag router and HBL2 line adapter"

  it should "combine interleaved microtiles, preserve guards and overlap reordered acknowledgements" in {
    val ep = EvuParams(enable = true)
    test(new EvuMatrixWritebackHarness(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.clock.setTimeout(10000)
      dut.io.flush.poke(false.B)
      dut.io.start.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.vector.req.valid.poke(false.B)
      dut.io.vector.resp.ready.poke(true.B)
      dut.io.memory.req.ready.poke(false.B)
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.memory.resp.bits.data.poke(0.U)
      dut.io.memory.resp.bits.tag.poke(0.U)
      dut.io.memory.resp.bits.error.poke(false.B)
      dut.io.memory.resp.bits.fault.poke(EvuFault.None)
      dut.clock.step()
      dut.io.start.poke(false.B)

      val guard = BigInt("ab" * 64, 16)
      val fullMask = (BigInt(1) << 64) - 1
      val halfMask = (BigInt(1) << 32) - 1
      val memory = mutable.Map.empty[Int, BigInt].withDefaultValue(guard)
      case class Response(tag: Int, data: BigInt, readyAt: Int)
      val pending = mutable.ArrayBuffer.empty[Response]
      var response = Option.empty[Response]
      var cycles = 0
      var reads = 0
      var writes = 0
      var maxOutstanding = 0
      var vectorReturned = false

      def tick(): Unit = {
        if (response.isEmpty) {
          val eligible = pending.indices.filter(i => pending(i).readyAt <= cycles)
          if (eligible.nonEmpty) { response = Some(pending.remove(eligible.last)) }
        }
        dut.io.memory.resp.valid.poke(response.nonEmpty.B)
        response.foreach { r =>
          dut.io.memory.resp.bits.tag.poke(r.tag.U)
          dut.io.memory.resp.bits.data.poke(r.data.U)
        }
        dut.io.memory.req.ready.poke((cycles % 5 != 0).B)
        val taken = response.nonEmpty && dut.io.memory.resp.ready.peek().litToBoolean
        if (dut.io.memory.req.valid.peek().litToBoolean && dut.io.memory.req.ready.peek().litToBoolean) {
          val req = dut.io.memory.req.bits
          req.bytes.expect(64.U)
          req.mask.expect(fullMask.U)
          val tag = req.tag.peek().litValue.toInt
          (pending.map(_.tag) ++ response.map(_.tag)) should not contain tag
          val addr = req.addr.peek().litValue.toInt
          addr % 64 shouldBe 0
          val write = req.write.peek().litToBoolean
          if (write) { writes += 1; memory(addr) = req.data.peek().litValue }
          else { reads += 1 }
          pending += Response(tag, if (write) BigInt(0) else memory(addr), cycles + 20 + (7 - tag) * 3)
          maxOutstanding = math.max(maxOutstanding, pending.size + response.size)
        }
        if (dut.io.vector.resp.valid.peek().litToBoolean) {
          dut.io.vector.resp.bits.tag.expect(0.U)
          dut.io.vector.resp.bits.error.expect(false.B)
          dut.io.vector.resp.bits.data.expect(guard.U)
          vectorReturned = true
        }
        dut.io.error.expect(false.B)
        dut.clock.step()
        if (taken) { response = None }
        cycles += 1
      }
      def beat(addr: Int, value: BigInt, mask: BigInt = halfMask, last: Boolean = false): Unit = {
        dut.io.in.bits.addr.poke(addr.U)
        dut.io.in.bits.data.poke(value.U)
        dut.io.in.bits.mask.poke(mask.U)
        dut.io.in.bits.last.poke(last.B)
        dut.io.in.valid.poke(true.B)
        var waited = 0
        while (!dut.io.in.ready.peek().litToBoolean && waited < 2000) { tick(); waited += 1 }
        dut.io.in.ready.expect(true.B)
        tick()
        dut.io.in.valid.poke(false.B)
      }

      // Same local tag (0) as the first matrix request, but a different owner.
      dut.io.vector.req.bits.addr.poke(0x8000.U)
      dut.io.vector.req.bits.data.poke(0.U)
      dut.io.vector.req.bits.mask.poke(halfMask.U)
      dut.io.vector.req.bits.bytes.poke(32.U)
      dut.io.vector.req.bits.write.poke(false.B)
      dut.io.vector.req.bits.tag.poke(0.U)
      dut.io.vector.req.valid.poke(true.B)
      while (!dut.io.vector.req.ready.peek().litToBoolean) { tick() }
      tick()
      dut.io.vector.req.valid.poke(false.B)

      // CUTE can visit all eight rows of a microtile before the next column
      // microtile provides the other half of each cache line.
      for (col <- 0 until 8; row <- 0 until 8) {
        beat(row * 512 + col * 32, BigInt(1 + row * 8 + col))
      }
      beat(0x4000 + 60, BigInt("0102030405060708", 16), 0x55, last = true)
      var waited = 0
      while ((!dut.io.done.peek().litToBoolean || dut.io.busy.peek().litToBoolean) && waited < 5000) {
        tick(); waited += 1
      }
      dut.io.done.expect(true.B)
      dut.io.busy.expect(false.B)
      pending shouldBe empty
      response shouldBe None
      vectorReturned shouldBe true
      for (row <- 0 until 8; line <- 0 until 4) {
        memory(row * 512 + line * 64) shouldBe
          ((BigInt(1 + row * 8 + line * 2 + 1) << 256) | BigInt(1 + row * 8 + line * 2))
      }
      val lowMask = BigInt("00ff00ff", 16) << 480
      val payload = BigInt("0102030405060708", 16)
      memory(0x4000) shouldBe ((guard & ~lowMask) | ((payload << 480) & lowMask))
      val highMask = BigInt("00ff00ff", 16)
      memory(0x4040) shouldBe ((guard & ~highMask) | ((payload >> 32) & highMask))
      reads shouldBe 3 // One VMU Get plus two partial-tail RMW Gets.
      writes shouldBe 34 // 64 half-lines -> 32 full writes, plus two tails.
      maxOutstanding should be > 1
    }
  }

  it should "drain flushed physical requests, cancel VMU tags and restart with clean matrix state" in {
    val ep = EvuParams(enable = true)
    test(new EvuMatrixWritebackHarness(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.start.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.vector.req.valid.poke(false.B)
      dut.io.vector.resp.ready.poke(true.B)
      dut.io.memory.req.ready.poke(false.B)
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.memory.resp.bits.data.poke(0.U)
      dut.io.memory.resp.bits.error.poke(false.B)
      dut.io.memory.resp.bits.fault.poke(EvuFault.None)
      dut.io.memory.resp.bits.tag.poke(0.U)
      dut.clock.step()
      dut.io.start.poke(false.B)
      def await(signal: Bool): Unit = {
        var cycles = 0
        while (!signal.peek().litToBoolean && cycles < 100) { dut.clock.step(); cycles += 1 }
        signal.peek().litToBoolean shouldBe true
      }
      def beat(addr: Int, value: Int, last: Boolean): Unit = {
        dut.io.in.bits.addr.poke(addr.U)
        dut.io.in.bits.data.poke(value.U)
        dut.io.in.bits.mask.poke(((BigInt(1) << 32) - 1).U)
        dut.io.in.bits.last.poke(last.B)
        dut.io.in.valid.poke(true.B)
        await(dut.io.in.ready)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
      }
      def response(tag: BigInt): Unit = {
        dut.io.memory.resp.bits.tag.poke(tag.U)
        dut.io.memory.resp.valid.poke(true.B)
        dut.io.memory.resp.ready.expect(true.B)
        dut.clock.step()
        dut.io.memory.resp.valid.poke(false.B)
      }

      beat(0, 1, last = false)
      beat(32, 2, last = true)
      await(dut.io.memory.req.valid)
      dut.io.memory.req.bits.write.expect(true.B)
      val staleTag = dut.io.memory.req.bits.tag.peek().litValue
      // Flush with the outgoing A packet queued but not accepted by HBL2.
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.memory.req.valid.expect(true.B)
      dut.io.memory.req.bits.tag.expect(staleTag.U)
      dut.io.memory.req.ready.poke(true.B)
      dut.clock.step()
      dut.io.memory.req.ready.poke(false.B)
      response(staleTag)
      dut.clock.step(3)
      dut.io.busy.expect(false.B)
      dut.io.done.expect(false.B)

      dut.io.vector.req.bits.addr.poke(512.U)
      dut.io.vector.req.bits.data.poke(0.U)
      dut.io.vector.req.bits.mask.poke(0.U)
      dut.io.vector.req.bits.bytes.poke(32.U)
      dut.io.vector.req.bits.write.poke(false.B)
      dut.io.vector.req.bits.tag.poke(7.U)
      dut.io.vector.req.valid.poke(true.B)
      await(dut.io.vector.req.ready)
      dut.clock.step()
      dut.io.vector.req.valid.poke(false.B)
      await(dut.io.memory.req.valid)
      val vectorTag = dut.io.memory.req.bits.tag.peek().litValue
      dut.io.memory.req.ready.poke(true.B)
      dut.clock.step()
      dut.io.memory.req.ready.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.vector.resp.valid.expect(false.B)
      response(vectorTag)
      await(dut.io.vector.resp.valid)
      dut.io.vector.resp.bits.tag.expect(7.U)
      dut.io.vector.resp.bits.error.expect(false.B)
      dut.clock.step(3)
      dut.io.busy.expect(false.B)

      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      beat(64, 3, last = false)
      beat(96, 4, last = true)
      await(dut.io.memory.req.valid)
      dut.io.memory.req.bits.addr.expect(64.U)
      dut.io.memory.req.bits.write.expect(true.B)
      dut.io.memory.req.bits.data.expect(((BigInt(4) << 256) | 3).U)
      val newTag = dut.io.memory.req.bits.tag.peek().litValue
      dut.io.memory.req.ready.poke(true.B)
      dut.clock.step()
      dut.io.memory.req.ready.poke(false.B)
      response(newTag)
      await(dut.io.done)
      dut.io.busy.expect(false.B)
      dut.io.error.expect(false.B)
    }
  }

  for (vlen <- Seq(512, 4096)) {
    it should s"elaborate VLEN=$vlen EvuTop connections to in-memory CHIRRTL without generating Verilog" in {
      implicit val p: Parameters = Parameters.empty.alterPartial {
        case freechips.rocketchip.tile.XLen => 64
        case freechips.rocketchip.tile.MaxHartIdBits => 1
        case freechips.rocketchip.rocket.PgLevels => 3
      }
      val ep = EvuParams(enable = true, vlenBits = vlen)
      val ir = ChiselStage.emitCHIRRTL(LazyModule(new EvuTopHarness(ep)).module)
      ir.contains("module EvuMatrixStoreBuffer") shouldBe true
      ir.contains("module EvuMemoryTagRouter") shouldBe true
      ir.contains("module EvuTop :") shouldBe true
    }
  }
}
