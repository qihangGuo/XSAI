package evu

import chisel3._
import chiseltest._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu._

import scala.collection.mutable

class EvuPackSlotSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "overlap independent PACK slots and preserve same-slot, full-line and store ordering" in {
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    val ep = EvuParams(enable = true, enableDifftest = true)
    test(LazyModule(new EvuSaturnExp2Shell(ep)).module)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        val input = 0x1000
        val desc = 0x1040
        val overwrite = 0x1080
        val output = 0x2000
        def repeat(word: BigInt, width: Int, count: Int): BigInt =
          (0 until count).map(i => word << (width * i)).sum
        val initial = repeat(0xa5, 8, 64)
        val memory = mutable.Map[Int, BigInt](
          input -> repeat(0x3f800000, 32, 16), // sixteen 1.0f inputs
          desc -> (0 until 16).map(i =>
            BigInt(if (i % 2 == 0) "bf800000" else "c0000000", 16) << (32 * i)).sum,
          overwrite -> initial)
        val responses = mutable.Queue.empty[(BigInt, BigInt)]
        val completed = mutable.Set.empty[Int]
        var serial = 0
        var cycles = 0
        val io = dut.io
        io.flush.poke(false.B)
        io.vstart.poke(0.U)
        io.command.valid.poke(false.B)
        io.completion.ready.poke(true.B)
        io.memory.req.ready.poke(true.B)
        io.memory.resp.bits.error.poke(false.B)
        io.memory.resp.bits.fault.poke(EvuFault.None)
        def tick(): Unit = {
          cycles += 1
          assert(cycles < 1500, s"timeout with completions $completed")
          io.memory.resp.valid.poke(responses.nonEmpty.B)
          val (tag, data) = responses.headOption.getOrElse((BigInt(0), BigInt(0)))
          io.memory.resp.bits.tag.poke(tag.U)
          io.memory.resp.bits.data.poke(data.U)
          val consumed = responses.nonEmpty && io.memory.resp.ready.peek().litToBoolean
          if (io.memory.req.valid.peek().litToBoolean) {
            val req = io.memory.req.bits
            val addr = req.addr.peek().litValue.toInt
            val write = req.write.peek().litToBoolean
            if (write) {
              req.mask.expect(((BigInt(1) << 64) - 1).U)
              memory(addr) = req.data.peek().litValue
            }
            responses.enqueue((req.tag.peek().litValue, if (write) BigInt(0) else memory(addr)))
          }
          if (io.completion.valid.peek().litToBoolean) {
            io.completion.bits.exception.expect(false.B)
            io.completion.bits.fflags.expect(0.U)
            assert(completed.add(io.completion.bits.jobId.peek().litValue.toInt))
          }
          dut.clock.step()
          if (consumed) responses.dequeue()
        }
        def send(op: UInt, reg: Int, base: Int): Int = {
          val id = serial
          serial += 1
          val c = io.command.bits
          c.opcode.poke(op)
          c.vd.poke(reg.U)
          c.vs2.poke(4.U)
          c.vs1.poke(0.U)
          c.sew.poke(2.U)
          c.vl.poke(16.U)
          c.base.poke(base.U)
          c.length.poke(64.U)
          c.stride.poke(0.S)
          c.aux.poke(0.U)
          c.write.poke((op.litValue == EvuOpcode.Store.litValue).B)
          c.chain.poke(false.B)
          c.jobId.poke(id.U)
          c.epoch.poke(0.U)
          c.config.configured.poke(false.B)
          c.pageMap.enabled.poke(false.B)
          io.command.valid.poke(true.B)
          while (!io.command.ready.peek().litToBoolean) tick()
          tick()
          io.command.valid.poke(false.B)
          id
        }
        def drain(): Unit = while (completed.size < serial) tick()
        def pack(slot: Int, index: Int): Unit =
          send(EvuBlock.Pack, 8, 12 | (index << 7) | (slot << 11))
        send(EvuOpcode.Load, 4, input)
        send(EvuOpcode.Load, 12, desc)
        drain()

        // Each input becomes FP4 code 2 (1.0) or 4 (2.0), selected per slot.
        for (slot <- 0 until 8) pack(slot, slot)
        send(EvuOpcode.Store, 8, output)
        drain()
        val line = (0 until 8).map(slot =>
          repeat(if (slot % 2 == 0) 0x22 else 0x44, 8, 8) << (64 * slot)).sum
        assert(memory(output) == line)
        val overlaps = io.packSlotIssues.peek().litValue
        assert(overlaps > 0 && io.packSlotCandidates.peek().litValue >= overlaps)

        // These writers and the preceding full-line load all overlap in bytes.
        send(EvuOpcode.Load, 8, overwrite)
        pack(3, 1)
        pack(3, 0)
        send(EvuOpcode.Store, 8, output + 64)
        // Younger PACK must not change what the preceding store observes.
        pack(3, 1)
        send(EvuOpcode.Store, 8, output + 128)
        drain()
        io.packSlotIssues.expect(overlaps.U)
        val slotMask = ((BigInt(1) << 64) - 1) << 192
        assert(memory(output + 64) == ((initial & ~slotMask) | (repeat(0x22, 8, 8) << 192)))
        assert(memory(output + 128) == ((initial & ~slotMask) | (repeat(0x44, 8, 8) << 192)))
      }
  }
}
