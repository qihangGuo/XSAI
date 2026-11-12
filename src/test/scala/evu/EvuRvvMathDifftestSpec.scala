package evu

import chisel3._
import chiseltest._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

import scala.collection.mutable
import scala.io.Source

class EvuRvvMathDifftestSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  def traceEnvironment: String = "EVU_RVV_MATH_TRACE"
  def expectedRecords: Int = 73
  behavior of "EVU RVV math through the Saturn backend"

  it should "match checked NEMU traces across widths, LMUL, E0, tails, flags and saturation" in {
    val path = sys.env.getOrElse(traceEnvironment, cancel(s"Set $traceEnvironment to the matching NEMU trace"))
    val source = Source.fromFile(path)
    val records = try source.getLines().filter(_.nonEmpty).toVector finally source.close()
    records.size shouldBe expectedRecords
    implicit val parameters: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    val ep = EvuParams(enable = true, enableDifftest = true)
    test(LazyModule(new EvuSaturnExp2Shell(ep, configurableLmul = true)).module)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.clock.setTimeout(200000)
        dut.io.command.valid.poke(false.B)
        dut.io.completion.ready.poke(true.B)
        dut.io.memory.req.ready.poke(true.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        dut.io.flush.poke(false.B)
        dut.io.vstart.poke(0.U)
        dut.io.lmul.get.poke(0.U)
        val memory = mutable.Map.empty[BigInt, BigInt]
        val responses = mutable.Queue.empty[(BigInt, BigInt)]
        var response = Option.empty[(BigInt, BigInt)]
        var completion = Option.empty[(Boolean, Int, Boolean)]
        def tick(): Unit = {
          if (response.isEmpty && responses.nonEmpty) response = Some(responses.dequeue())
          dut.io.memory.resp.valid.poke(response.nonEmpty.B)
          response.foreach { case (tag, data) =>
            dut.io.memory.resp.bits.tag.poke(tag.U)
            dut.io.memory.resp.bits.data.poke(data.U)
          }
          val accepted = response.nonEmpty && dut.io.memory.resp.ready.peek().litToBoolean
          if (dut.io.memory.req.valid.peek().litToBoolean) {
            dut.io.memory.req.bits.write.expect(false.B)
            responses.enqueue((dut.io.memory.req.bits.tag.peek().litValue,
              memory(dut.io.memory.req.bits.addr.peek().litValue)))
          }
          if (dut.io.completion.valid.peek().litToBoolean && dut.io.completion.ready.peek().litToBoolean) {
            val result = dut.io.completion.bits
            completion = Some((result.exception.peek().litToBoolean, result.fflags.peek().litValue.toInt,
              result.vxsat.peek().litToBoolean))
          }
          dut.clock.step()
          if (accepted) response = None
        }
        def send(op: Int, sew: Int, vl: Int, dest: Int, source: Int = 4, source1: Int = 0,
            aux: Int = 0, base: Int = 0, flags: Int = 0, saturated: Boolean = false,
            backpressure: Boolean = false): Unit = {
          completion = None
          val command = dut.io.command.bits
          command.pc.poke(0.U)
          command.config.configured.poke(false.B)
          command.config.valid.poke(false.B)
          command.config.vl.poke(0.U)
          command.config.vtype.poke(0.U)
          command.config.vill.poke(false.B)
          command.stride.poke(0.S)
          command.write.poke(false.B)
          command.chain.poke(false.B)
          command.jobId.poke(0.U)
          command.epoch.poke(0.U)
          command.pageMap.enabled.poke(false.B)
          command.pageMap.base.poke(0.U)
          command.pageMap.length.poke(0.U)
          command.pageMap.ppn.foreach(_.poke(0.U))
          command.opcode.poke(op.U)
          command.sew.poke(sew.U)
          command.vl.poke(vl.U)
          command.vd.poke(dest.U)
          command.vs2.poke(source.U)
          command.vs1.poke(source1.U)
          command.aux.poke(aux.U)
          command.base.poke(base.U)
          command.length.poke(ep.vlenBytes.U)
          dut.io.command.valid.poke(true.B)
          dut.io.completion.ready.poke((!backpressure).B)
          var cycles = 0
          while (!dut.io.command.ready.peek().litToBoolean && cycles < 20000) { tick(); cycles += 1 }
          dut.io.command.ready.expect(true.B)
          tick()
          dut.io.command.valid.poke(false.B)
          if (backpressure) {
            while (!dut.io.completion.valid.peek().litToBoolean && cycles < 20000) { tick(); cycles += 1 }
            dut.io.completion.valid.expect(true.B)
            val held = dut.io.completion.bits.peek()
            for (_ <- 0 until 5) {
              tick()
              dut.io.completion.bits.expect(held)
            }
            dut.io.completion.ready.poke(true.B)
          }
          while (completion.isEmpty && cycles < 20000) { tick(); cycles += 1 }
          completion shouldBe Some((false, flags, saturated))
        }
        def pack(bytes: Seq[Int]): BigInt = bytes.zipWithIndex.foldLeft(BigInt(0)) {
          case (bits, (byte, index)) => bits | (BigInt(byte) << (8 * index))
        }
        def load(reg: Int, bytes: Seq[Int]): Unit = {
          val base = 0x1000 + reg * ep.vlenBytes
          bytes.grouped(ep.mlenBytes).zipWithIndex.foreach { case (line, offset) =>
            memory(BigInt(base + offset * ep.mlenBytes)) = pack(line)
          }
          send(0x10, 0, ep.vlenBytes, reg, base = base)
        }
        for ((record, caseIndex) <- records.zipWithIndex) {
          val fields = record.split(" ")
          val instruction = java.lang.Long.parseLong(fields(0), 16)
          val vtype = fields(1).toInt
          val vl = fields(2).toInt
          val flags = fields(3).toInt
          val saturated = fields(4).toInt != 0
          val bytes = fields.drop(5).map(Integer.parseInt(_, 16)).toSeq
          bytes.size shouldBe 10 * ep.vlenBytes
          val input = bytes.take(4 * ep.vlenBytes)
          val other = bytes.slice(4 * ep.vlenBytes, 6 * ep.vlenBytes)
          val expected = bytes.drop(6 * ep.vlenBytes)
          withClue(s"case=$caseIndex instruction=${fields(0)} vtype=$vtype vl=$vl: ") {
            dut.io.lmul.get.poke(0.U)
            for (reg <- 0 until 4) {
              load(4 + reg, input.slice(reg * ep.vlenBytes, (reg + 1) * ep.vlenBytes))
              load(8 + reg, Seq.fill(ep.vlenBytes)(0xa5))
            }
            for (reg <- 0 until 2)
              load(12 + reg, other.slice(reg * ep.vlenBytes, (reg + 1) * ep.vlenBytes))
            dut.io.lmul.get.poke((vtype & 7).U)
            val raw = (instruction >>> 25).toInt
            val canonical = Map(0x62 -> 0x60, 0x63 -> 0x61, 0x65 -> 0x64).getOrElse(raw, raw)
            send(canonical, vtype >> 3, vl, 8, source1 = ((instruction >>> 15) & 31).toInt,
              aux = if (raw != canonical) 64 else 0, flags = flags, saturated = saturated,
              backpressure = caseIndex % 7 == 0)
            for (chunk <- 0 until 4 * ep.vlenBits / ep.dlenBits) {
              dut.io.vrf(8 * ep.vlenBits / ep.dlenBits + chunk).expect(
                pack(expected.slice(chunk * ep.dlenBytes, (chunk + 1) * ep.dlenBytes)).U)
            }
          }
        }
        dut.io.lmul.get.poke(0.U)
        val fours = Seq.fill(ep.vlenBytes / 4)(Seq(0, 0, 128, 64)).flatten
        load(4, fours)
        send(0x60, 2, 128, 4, source = 4, aux = 64, backpressure = true)
        val aliasExpected = Seq(0, 0, 0, 64) ++ fours.drop(4)
        for (chunk <- 0 until ep.vlenBits / ep.dlenBits)
          dut.io.vrf(4 * ep.vlenBits / ep.dlenBits + chunk).expect(
            pack(aliasExpected.slice(chunk * ep.dlenBytes, (chunk + 1) * ep.dlenBytes)).U)
        dut.io.flush.poke(true.B)
        tick()
        dut.io.flush.poke(false.B)
        for (_ <- 0 until 5) tick()
        load(4, fours)
        load(8, Seq.fill(ep.vlenBytes)(0xa5))
        send(0x64, 2, 13, 8)
        dut.io.vrf(8 * ep.vlenBits / ep.dlenBits).expect(
          pack(Seq.fill(13)(Seq(0, 0, 127, 62)).flatten ++ Seq.fill(12)(0xa5)).U)
      }
  }
}
