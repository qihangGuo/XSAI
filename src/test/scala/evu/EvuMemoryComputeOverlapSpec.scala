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

class EvuMemoryComputeOverlapSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU ordered memory and independent computation"

  // Full VMU + arithmetic interpretation is extremely slow. The default
  // regression exercises the production scoreboard separately; retain this
  // integration case behind an explicit opt-in or use server validation.
  it should "overlap independent work, retain register hazards and discard flushed bus faults" in {
    assume(sys.env.get("EVU_LONG_TREADLE").contains("1"), "set EVU_LONG_TREADLE=1 for the full backend slow test")
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    // Keep the production eight-bank/1RW topology, using one 64-bit lane to
    // make full-backend FIRRTL interpretation practical without Verilator.
    val ep = EvuParams(enable = true, enableDifftest = true, vlenBits = 128,
      dlenBits = 64, mlenBits = 64, lanes = 1, dispatchDepth = 1,
      loadIssueDepth = 1, storeIssueDepth = 1, executeIssueDepth = 1,
      specialIssueDepth = 1, loadResponseDepth = 8)
    test(LazyModule(new EvuSaturnMemoryTagHarness(ep, integerOverlapOnly = true)).module)
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.clock.setTimeout(3000)
        dut.io.flush.poke(false.B)
        dut.io.command.valid.poke(false.B)
        dut.io.completion.ready.poke(true.B)
        dut.io.memory.req.ready.poke(true.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        case class Request(tag: BigInt, addr: BigInt, write: Boolean, data: BigInt)
        val pending = mutable.ArrayBuffer.empty[Request]
        val completed = mutable.Map.empty[Int, (Boolean, BigInt, BigInt)]
        def tick(): Unit = {
          if (dut.io.memory.req.valid.peek().litToBoolean) {
            val r = dut.io.memory.req.bits
            pending += Request(r.tag.peek().litValue, r.addr.peek().litValue,
              r.write.peek().litToBoolean, r.data.peek().litValue)
          }
          if (dut.io.completion.valid.peek().litToBoolean && dut.io.completion.ready.peek().litToBoolean) {
            val c = dut.io.completion.bits
            val id = c.jobId.peek().litValue.toInt
            completed.contains(id) shouldBe false
            completed(id) = (c.exception.peek().litToBoolean, c.cause.peek().litValue, c.tval.peek().litValue)
          }
          dut.clock.step()
        }
        def until(condition: => Boolean): Unit = {
          var cycles = 0
          while (!condition && cycles < 180) { tick(); cycles += 1 }
          condition shouldBe true
        }
        def configure(opcode: UInt, job: Int, vd: Int, vs1: Int = 1, vs2: Int = 2): Unit = {
          val c = dut.io.command.bits
          c.pc.poke((0x80000000L + job * 4).U)
          c.config.configured.poke(false.B)
          c.config.valid.poke(false.B)
          c.config.vl.poke(0.U)
          c.config.vtype.poke(0.U)
          c.config.vill.poke(false.B)
          c.opcode.poke(opcode)
          c.sew.poke(2.U)
          c.vd.poke(vd.U)
          c.vs1.poke(vs1.U)
          c.vs2.poke(vs2.U)
          c.vl.poke(4.U)
          c.base.poke((0x1000 + vd * 0x100).U)
          c.stride.poke(0.S)
          c.length.poke(ep.vlenBytes.U)
          c.aux.poke(0.U)
          c.write.poke(false.B)
          c.chain.poke(false.B)
          c.jobId.poke(job.U)
          c.epoch.poke(0.U)
          c.pageMap.enabled.poke(false.B)
          c.pageMap.base.poke(0.U)
          c.pageMap.length.poke(0.U)
          c.pageMap.ppn.foreach(_.poke(0.U))
        }
        def issue(opcode: UInt, job: Int, vd: Int, vs1: Int = 1, vs2: Int = 2): Unit = {
          completed.remove(job)
          configure(opcode, job, vd, vs1, vs2)
          dut.io.command.valid.poke(true.B)
          until(dut.io.command.ready.peek().litToBoolean)
          tick()
          dut.io.command.valid.poke(false.B)
        }
        def blocked(opcode: UInt, job: Int, vd: Int, vs1: Int = 1, vs2: Int = 2): Unit = {
          configure(opcode, job, vd, vs1, vs2)
          dut.io.command.valid.poke(true.B)
          for (_ <- 0 until 3) { dut.io.command.ready.expect(false.B); tick() }
          dut.io.command.valid.poke(false.B)
        }
        def respond(r: Request, value: Int = 11, fault: Boolean = false): Unit = {
          dut.io.memory.resp.valid.poke(true.B)
          dut.io.memory.resp.bits.tag.poke(r.tag.U)
          dut.io.memory.resp.bits.data.poke((0 until ep.mlenBytes / 4).map(i => BigInt(value) << (32 * i)).reduce(_ | _).U)
          dut.io.memory.resp.bits.error.poke(fault.B)
          dut.io.memory.resp.bits.fault.poke(if (fault) EvuFault.Bus else EvuFault.None)
          until(dut.io.memory.resp.ready.peek().litToBoolean)
          tick()
          dut.io.memory.resp.valid.poke(false.B)
        }
        def drain(value: Int, faultIndex: Int = -1): Unit = {
          until(pending.size == 2)
          val rs = pending.toVector
          pending.clear()
          // Return in reverse order to exercise local/global tag routing.
          for (i <- rs.indices.reverse) respond(rs(i), value, i == faultIndex)
        }
        def done(job: Int, fault: Boolean = false): Unit = {
          until(completed.contains(job))
          completed(job)._1 shouldBe fault
          completed(job)._2 shouldBe (if (fault) BigInt(6) else BigInt(0))
        }
        // RegisterAccess clears every physical EG through normal 1RW writes.
        dut.clock.step(72)
        issue(EvuOpcode.Load, 0, 1); drain(3); done(0)
        issue(EvuOpcode.Load, 1, 2); drain(7); done(1)

        issue(EvuOpcode.Load, 2, 8)
        until(pending.size == 2)
        blocked(EvuOpcode.Add, 4, 4, vs1 = 8) // RAW
        blocked(EvuOpcode.Add, 4, 8) // WAW
        blocked(EvuOpcode.Load, 4, 10) // memory order, despite independent registers
        issue(EvuOpcode.Add, 3, 3)
        done(3) // Must complete while the earlier load still has no responses.
        completed.contains(2) shouldBe false
        dut.io.overlap.peek().litValue should be > BigInt(0)
        for (chunk <- 0 until 2) {
          dut.io.vrf(6 + chunk).expect((0 until ep.dlenBits / 32).map(i => BigInt(10) << (32 * i)).reduce(_ | _).U)
        }
        drain(11); done(2)
        issue(EvuOpcode.Add, 4, 4, vs1 = 8); done(4)

        issue(EvuOpcode.Store, 5, 3)
        until(pending.size == 2)
        blocked(EvuOpcode.Add, 6, 3) // WAR: the store retains its read reservation.
        issue(EvuOpcode.Add, 6, 5)
        done(6)
        drain(0, faultIndex = 0); done(5, fault = true)
        completed(5)._3 shouldBe BigInt(0x1300)

        // Memory may also enter behind unfinished independent arithmetic.
        dut.io.completion.ready.poke(false.B)
        issue(EvuOpcode.Add, 0, 6)
        until(dut.io.completion.valid.peek().litToBoolean)
        issue(EvuOpcode.Load, 1, 9)
        until(pending.size == 2)
        dut.io.completion.ready.poke(true.B)
        done(0)
        drain(17); done(1)

        issue(EvuOpcode.Load, 2, 8)
        until(pending.size == 2)
        val cancelled = pending.toVector
        pending.clear()
        dut.io.flush.poke(true.B)
        configure(EvuOpcode.Add, 7, 7)
        dut.io.command.valid.poke(true.B)
        dut.io.command.ready.expect(false.B)
        tick()
        dut.io.command.valid.poke(false.B)
        dut.io.flush.poke(false.B)
        completed.clear()
        dut.clock.step(72)
        issue(EvuOpcode.Load, 3, 10)
        until(pending.size == 2)
        cancelled.foreach(r => respond(r, fault = true))
        drain(19)
        done(3)
        completed.contains(2) shouldBe false
        until(!dut.io.busy.peek().litToBoolean)
      }
  }
}
