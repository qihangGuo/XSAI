package evu

import chisel3._
import chiseltest._
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

import scala.collection.mutable

class EvuQuantTailSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Quantization from Saturn's VRF into CUTE"

  it should "preserve the VRF, mask CUTE tail bytes and exclude inactive lanes from saturation" in {
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    // SRAM definitions are cached across elaborations; use a different depth from the Exp2 test.
    val ep = EvuParams(enable = true, enableDifftest = true, vlenBits = 1024)
    test(LazyModule(new EvuSaturnExp2Shell(ep)).module)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.clock.setTimeout(30000)
        dut.io.command.valid.poke(false.B)
        dut.io.completion.ready.poke(false.B)
        dut.io.memory.req.ready.poke(true.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        dut.io.flush.poke(false.B)
        dut.io.vstart.poke(0.U)
        val responses = mutable.Queue.empty[(BigInt, BigInt)]
        val cuteWrites = mutable.ArrayBuffer.empty[(BigInt, BigInt, BigInt)]
        var memoryData = BigInt(0)
        def pack(values: Seq[BigInt], width: Int): BigInt =
          values.zipWithIndex.foldLeft(BigInt(0)) { case (bits, (value, i)) =>
            bits | ((value & ((BigInt(1) << width) - 1)) << (i * width))
          }
        def words(values: Seq[Int]): BigInt = pack(values.map(BigInt(_)), 32)
        def tick(): Unit = {
          dut.io.memory.resp.valid.poke(responses.nonEmpty.B)
          responses.headOption.foreach { case (tag, data) =>
            dut.io.memory.resp.bits.tag.poke(tag.U)
            dut.io.memory.resp.bits.data.poke(data.U)
          }
          val taken = responses.nonEmpty && dut.io.memory.resp.ready.peek().litToBoolean
          val request = dut.io.memory.req.valid.peek().litToBoolean
          val tag = dut.io.memory.req.bits.tag.peek().litValue
          val offset = dut.io.memory.req.bits.addr.peek().litValue - 0x1000
          if (request) dut.io.memory.req.bits.write.expect(false.B)
          if (dut.io.cuteWrite.valid.peek().litToBoolean) {
            cuteWrites += ((dut.io.cuteWrite.bits.entry.peek().litValue,
              dut.io.cuteWrite.bits.data.peek().litValue, dut.io.cuteWrite.bits.byteMask.peek().litValue))
          }
          dut.clock.step()
          if (taken) responses.dequeue()
          if (request) responses.enqueue((tag,
            (memoryData >> (offset.toInt * 8)) & ((BigInt(1) << ep.mlenBits) - 1)))
        }
        def await(signal: Bool): Unit = {
          var cycles = 0
          while (!signal.peek().litToBoolean && cycles < 500) { tick(); cycles += 1 }
          signal.expect(true.B)
        }
        def send(opcode: UInt, sew: Int, vl: Int, vd: Int = 4, aux: Int = 0): Unit = {
          val c = dut.io.command.bits
          c.opcode.poke(opcode)
          c.vd.poke(vd.U)
          c.vs1.poke(2.U)
          c.vs2.poke((if (opcode.litValue == EvuOpcode.SetCfg.litValue) 3 else 1).U)
          c.sew.poke(sew.U)
          c.vl.poke(vl.U)
          c.base.poke(0x1000.U)
          if (opcode.litValue == EvuOpcode.SetCfg.litValue) c.base.poke(0.U)
          c.stride.poke(0.S)
          c.length.poke(ep.vlenBytes.U)
          c.aux.poke(aux.U)
          c.write.poke(false.B)
          c.chain.poke(false.B)
          c.jobId.poke(7.U)
          c.epoch.poke(0.U)
          c.pageMap.enabled.poke(false.B)
          c.pageMap.base.poke(0.U)
          c.pageMap.length.poke(0.U)
          c.pageMap.ppn.foreach(_.poke(0.U))
          dut.io.command.valid.poke(true.B)
          await(dut.io.command.ready)
          tick()
          dut.io.command.valid.poke(false.B)
        }
        def finish(sat: Boolean = false): Unit = {
          await(dut.io.completion.valid)
          val opcode = dut.io.command.bits.opcode.peek().litValue
          val dirtyVs = dut.io.command.bits.vl.peek().litValue != 0 &&
            opcode != EvuOpcode.SetCfg.litValue && opcode != EvuOpcode.QuantToCute.litValue
          for (_ <- 0 until 3) {
            dut.io.completion.bits.exception.expect(false.B)
            dut.io.completion.bits.fflags.expect(0.U)
            dut.io.completion.bits.vxsat.expect(sat.B)
            dut.io.completion.bits.dirtyVs.expect(dirtyVs.B)
            tick()
          }
          dut.io.completion.ready.poke(true.B)
          tick()
          dut.io.completion.ready.poke(false.B)
        }
        def load(reg: Int, data: BigInt): Unit = {
          memoryData = data
          send(EvuOpcode.Load, 2, ep.vlenBytes / 4, vd = reg)
          finish()
        }
        def expectReg(reg: Int, data: BigInt): Unit = {
          val mask = (BigInt(1) << ep.dlenBits) - 1
          for (chunk <- 0 until ep.vlenBits / ep.dlenBits) {
            dut.io.vrf(reg * (ep.vlenBits / ep.dlenBits) + chunk)
              .expect(((data >> (ep.dlenBits * chunk)) & mask).U)
          }
        }
        val inputs = (0 until ep.vlenBytes / 4).map(i => i - 7)
        val sentinel = pack((0 until ep.vlenBytes).map(i => BigInt(0x80 + i)), 8)
        load(1, words(inputs))
        load(2, words(Seq.fill(inputs.size)(2)))
        load(3, words(Seq.fill(inputs.size)(1)))
        send(EvuOpcode.SetCfg, 2, 0, vd = 0)
        finish()
        val converted = words(inputs.map(_ * 2 + 1))
        load(4, sentinel)
        for (sew <- 0 to 2;
             bytes <- Seq(0, 1, 2, 3, 4, 30, 31, 32, 33, 34, 35, 36, 60, 62, 63, 64,
               ep.vlenBytes - 1, ep.vlenBytes)
               if bytes % (1 << sew) == 0) {
          cuteWrites.clear()
          send(EvuOpcode.QuantToCute, sew, bytes >> sew)
          finish()
          withClue(s"sew=$sew bytes=$bytes: ") {
            cuteWrites.size shouldBe ((bytes + ep.dlenBytes - 1) / ep.dlenBytes)
            for (((entry, data, mask), index) <- cuteWrites.zipWithIndex) {
              val activeBytes = math.min(ep.dlenBytes, bytes - ep.dlenBytes * index)
              val activeBits = (BigInt(1) << (activeBytes * 8)) - 1
              entry shouldBe BigInt(index)
              mask shouldBe ((BigInt(1) << activeBytes) - 1)
              (data & activeBits) shouldBe ((converted >> (ep.dlenBits * index)) & activeBits)
            }
            expectReg(4, sentinel)
            expectReg(1, words(inputs))
            expectReg(2, words(Seq.fill(inputs.size)(2)))
            expectReg(3, words(Seq.fill(inputs.size)(1)))
          }
        }

        // Only the first nine words are live; the remaining words would saturate.
        load(1, words(Seq.fill(9)(1) ++ Seq.fill(inputs.size - 9)(1000)))
        send(EvuOpcode.SetCfg, 0, 0, vd = 0)
        finish()
        send(EvuOpcode.QuantToCute, 2, 9)
        finish(sat = false)
        load(1, words(Seq(1000) ++ Seq.fill(inputs.size - 1)(1)))
        send(EvuOpcode.QuantToCute, 2, 0)
        finish(sat = false)
        send(EvuOpcode.QuantToCute, 2, 9)
        finish(sat = true) // Saturation in the first chunk must survive the last chunk.
        expectReg(4, sentinel)
      }
  }
}
