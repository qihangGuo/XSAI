package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import xiangshan.evu._

class EvuChainingSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Production VMU/VRF EG chaining"
  it should "forward a dependent load compute store burst and complete after physical writes" in {
    if (!sys.env.get("EVU_LONG_TREADLE").contains("1")) {
      cancel("Full VMU/FP/VRF Treadle lowering is slow; enable EVU_LONG_TREADLE=1 explicitly")
    }
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    val ep = EvuParams(enable = true, enableDifftest = true, vlenBits = 128,
      dlenBits = 64, mlenBits = 64, lanes = 1)
    test(LazyModule(new EvuSaturnMemoryTagHarness(ep, integerOverlapOnly = true)).module)
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        def clear(x: Data): Unit = x match {
          case b: Bool => b.poke(false.B)
          case u: UInt => u.poke(0.U)
          case s: SInt => s.poke(0.S)
          case v: Vec[_] => v.foreach(clear)
          case b: Bundle => b.elements.values.foreach(clear)
        }
        clear(dut.io.command.bits)
        clear(dut.io.memory.resp.bits)
        dut.io.command.valid.poke(false.B)
        dut.io.flush.poke(false.B)
        dut.io.completion.ready.poke(true.B)
        dut.io.memory.req.ready.poke(true.B)
        dut.io.memory.resp.valid.poke(false.B)
        val memory = mutable.Map[BigInt, BigInt](
          BigInt(0x1000) -> BigInt("0000000200000001", 16),
          BigInt(0x1008) -> BigInt("0000000400000003", 16))
        val pending = mutable.Queue.empty[(BigInt, BigInt, Int)]
        val completed = mutable.Set.empty[Int]
        var cycle = 0
        def tick(): Unit = {
          val sending = pending.headOption.filter(_._3 <= cycle)
          dut.io.memory.resp.valid.poke(sending.nonEmpty.B)
          sending.foreach { case (tag, data, _) =>
            dut.io.memory.resp.bits.tag.poke(tag.U)
            dut.io.memory.resp.bits.data.poke(data.U)
          }
          if (dut.io.completion.valid.peek().litToBoolean) {
            dut.io.completion.bits.exception.expect(false.B)
            completed += dut.io.completion.bits.jobId.peek().litValue.toInt
          }
          val responseFire = sending.nonEmpty && dut.io.memory.resp.ready.peek().litToBoolean
          val request = if (dut.io.memory.req.valid.peek().litToBoolean) {
            val r = dut.io.memory.req.bits
            val addr = r.addr.peek().litValue
            val write = r.write.peek().litToBoolean
            val data = if (write) r.data.peek().litValue else memory(addr)
            if (write) memory(addr) = data
            Some((r.tag.peek().litValue, data, cycle + 3))
          } else None
          dut.clock.step()
          if (responseFire) pending.dequeue()
          request.foreach(pending.enqueue(_))
          cycle += 1
        }
        def send(op: UInt, dest: Int, a: Int, b: Int, addr: Int, job: Int): Unit = {
          val c = dut.io.command.bits
          c.opcode.poke(op)
          c.vd.poke(dest.U)
          c.vs1.poke(a.U)
          c.vs2.poke(b.U)
          c.base.poke(addr.U)
          c.jobId.poke(job.U)
          dut.io.command.valid.poke(true.B)
          var wait = 0
          while (!dut.io.command.ready.peek().litToBoolean && wait < 200) { tick(); wait += 1 }
          dut.io.command.ready.expect(true.B)
          tick()
          dut.io.command.valid.poke(false.B)
        }
        send(EvuOpcode.Load, 1, 0, 0, 0x1000, 0)
        send(EvuOpcode.Add, 2, 1, 1, 0, 1)
        send(EvuOpcode.Store, 2, 0, 0, 0x2000, 2)
        while (completed.size < 3 && cycle < 500) { tick() }
        completed shouldBe Set(0, 1, 2)
        memory(BigInt(0x2000)) shouldBe BigInt("0000000400000002", 16)
        memory(BigInt(0x2008)) shouldBe BigInt("0000000800000006", 16)
        dut.io.vrf(4).expect(BigInt("0000000400000002", 16).U)
        dut.io.vrf(5).expect(BigInt("0000000800000006", 16).U)
      }
  }
}
