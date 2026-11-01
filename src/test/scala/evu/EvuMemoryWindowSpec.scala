package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

import scala.collection.mutable

class EvuMemoryWindowSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU per-command memory contexts"

  it should "isolate translations and faults, capture stores before reuse and drain stale epochs" in {
    assume(sys.env.get("EVU_LONG_TREADLE").contains("1"), "set EVU_LONG_TREADLE=1 for the full backend slow test")
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    val ep = EvuParams(enable = true, enableDifftest = true, vlenBits = 128,
      dlenBits = 64, mlenBits = 64, lanes = 1, memoryCommandDepth = 2,
      loadResponseDepth = 8)
    test(LazyModule(new EvuSaturnMemoryTagHarness(ep, integerOverlapOnly = true)).module)
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.clock.setTimeout(5000)
        dut.io.flush.poke(false.B)
        dut.io.command.valid.poke(false.B)
        dut.io.completion.ready.poke(true.B)
        dut.io.memory.req.ready.poke(true.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        case class Request(tag: BigInt, addr: BigInt, job: Int, epoch: Int, write: Boolean, data: BigInt)
        case class Completion(error: Boolean, cause: BigInt, tval: BigInt, epoch: Int)
        val pending = mutable.ArrayBuffer.empty[Request]
        val completed = mutable.Map.empty[Int, Completion]
        def tick(): Unit = {
          if (dut.io.memory.req.valid.peek().litToBoolean && dut.io.memory.req.ready.peek().litToBoolean) {
            val r = dut.io.memory.req.bits
            pending += Request(r.tag.peek().litValue, r.addr.peek().litValue,
              r.jobId.peek().litValue.toInt, r.epoch.peek().litValue.toInt,
              r.write.peek().litToBoolean, r.data.peek().litValue)
          }
          if (dut.io.completion.valid.peek().litToBoolean && dut.io.completion.ready.peek().litToBoolean) {
            val c = dut.io.completion.bits
            val id = c.jobId.peek().litValue.toInt
            completed.contains(id) shouldBe false
            completed(id) = Completion(c.exception.peek().litToBoolean,
              c.cause.peek().litValue, c.tval.peek().litValue, c.epoch.peek().litValue.toInt)
          }
          dut.clock.step()
        }
        def until(condition: => Boolean): Unit = {
          var cycles = 0
          while (!condition && cycles < 240) { tick(); cycles += 1 }
          condition shouldBe true
        }
        def issue(op: UInt, job: Int, reg: Int, base: Int = 0x6000,
                  mappedPpn: Int = 0, epoch: Int = 0): Unit = {
          val c = dut.io.command.bits
          c.pc.poke((0x80000000L + job * 4).U)
          c.config.configured.poke(false.B)
          c.config.valid.poke(false.B)
          c.config.vl.poke(0.U)
          c.config.vtype.poke(0.U)
          c.config.vill.poke(false.B)
          c.opcode.poke(op)
          c.sew.poke(2.U)
          c.vd.poke(reg.U)
          c.vs1.poke(2.U)
          c.vs2.poke(2.U)
          c.vl.poke(4.U)
          c.base.poke(base.U)
          c.stride.poke(0.S)
          c.length.poke(ep.vlenBytes.U)
          c.aux.poke(0.U)
          c.write.poke(false.B)
          c.chain.poke(false.B)
          c.jobId.poke(job.U)
          c.epoch.poke(epoch.U)
          c.pageMap.enabled.poke((mappedPpn != 0).B)
          c.pageMap.base.poke(base.U)
          c.pageMap.length.poke(ep.vlenBytes.U)
          c.pageMap.ppn.foreach(_.poke(mappedPpn.U))
          dut.io.command.valid.poke(true.B)
          until(dut.io.command.ready.peek().litToBoolean)
          tick()
          dut.io.command.valid.poke(false.B)
        }
        def requests(job: Int): Seq[Request] = pending.filter(_.job == job).toVector
        def respond(r: Request, value: Int = 3, error: Boolean = false): Unit = {
          dut.io.memory.resp.valid.poke(true.B)
          dut.io.memory.resp.bits.tag.poke(r.tag.U)
          dut.io.memory.resp.bits.data.poke(((BigInt(value) << 32) | value).U)
          dut.io.memory.resp.bits.error.poke(error.B)
          dut.io.memory.resp.bits.fault.poke(if (error) EvuFault.Bus else EvuFault.None)
          until(dut.io.memory.resp.ready.peek().litToBoolean)
          tick()
          dut.io.memory.resp.valid.poke(false.B)
          pending -= r
        }
        def done(job: Int, error: Boolean = false): Unit = {
          until(completed.contains(job))
          completed(job).error shouldBe error
          completed(job).cause shouldBe (if (error) BigInt(6) else BigInt(0))
        }
        dut.clock.step(72) // Clear all physical EGs through their existing 1RW ports.

        issue(EvuOpcode.Load, 0, 1, base = 0x4000, mappedPpn = 0x20)
        until(requests(0).size == 2)
        issue(EvuOpcode.Load, 1, 8, base = 0x9000, mappedPpn = 0x30)
        until(requests(1).size == 2) // Neither command has received a response.
        requests(0).map(_.addr) shouldBe Seq(BigInt(0x20000), BigInt(0x20008))
        requests(1).map(_.addr) shouldBe Seq(BigInt(0x30000), BigInt(0x30008))
        val failed = requests(1).last
        respond(failed, error = true)
        requests(1).foreach(r => respond(r))
        requests(0).reverse.foreach(r => respond(r))
        done(0)
        done(1, error = true)
        completed(1).tval shouldBe BigInt(0x9008) // Original VA, not another command's map.

        issue(EvuOpcode.Load, 2, 2)
        until(requests(2).size == 2)
        requests(2).foreach(r => respond(r, value = 7))
        done(2)
        issue(EvuOpcode.Store, 3, 1, base = 0x5000)
        until(requests(3).size == 2)
        val stores = requests(3)
        stores.foreach { r =>
          r.write shouldBe true
          r.data shouldBe ((BigInt(3) << 32) | 3)
        }
        issue(EvuOpcode.Add, 4, 1)
        done(4) // Captured store data survives a later write to its source VRF.
        completed.contains(3) shouldBe false
        dut.io.vrf(2).expect(((BigInt(14) << 32) | 14).U)
        issue(EvuOpcode.Load, 5, 9, base = 0x5000)
        for (_ <- 0 until 12) { tick(); requests(5) shouldBe empty }
        stores.reverse.foreach(r => respond(r))
        done(3)
        until(requests(5).size == 2) // Overlapping load waits for the store's Ack.
        requests(5).foreach(r => respond(r))
        done(5)

        issue(EvuOpcode.Store, 8, 1, base = 0xb000)
        until(requests(8).size == 2)
        val faultyStore = requests(8)
        respond(faultyStore.head)
        respond(faultyStore.last, error = true) // Fault on the final Ack/complete edge.
        done(8, error = true)
        completed(8).tval shouldBe BigInt(0xb008)

        issue(EvuOpcode.Load, 6, 10, base = 0x7000)
        until(requests(6).size == 2)
        issue(EvuOpcode.Load, 7, 11, base = 0x8000)
        until(requests(7).size == 2)
        val stale = pending.toVector
        dut.io.flush.poke(true.B)
        tick()
        dut.io.flush.poke(false.B)
        completed.clear()
        dut.clock.step(72)
        issue(EvuOpcode.Load, 0, 10, base = 0xa000, epoch = 1)
        until(requests(0).size == 2)
        stale.reverse.foreach(r => respond(r, error = true))
        requests(0).foreach(r => respond(r, value = 19))
        done(0)
        completed(0).epoch shouldBe 1
        completed.keySet shouldBe Set(0)
        until(!dut.io.busy.peek().litToBoolean)
      }
  }
}
