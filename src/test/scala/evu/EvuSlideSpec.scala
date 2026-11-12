package evu

import chisel3._
import chiseltest._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

import scala.collection.mutable

class EvuSlideSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU slides through Saturn"

  it should "move elements across groups, preserve tails, accept down aliases and reject retired WAIT" in {
    implicit val parameters: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    val ep = EvuParams(enable = true, enableDifftest = true)
    test(LazyModule(new EvuSaturnExp2Shell(ep, configurableLmul = true)).module)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.clock.setTimeout(2000000)
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
        def send(op: Int, sew: Int, vl: Int, dest: Int, source: Int = 4, base: BigInt = 0,
            illegal: Boolean = false, backpressure: Boolean = false): Unit = {
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
          command.vs1.poke(31.U)
          command.aux.poke(0.U)
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
            for (_ <- 0 until 5) { tick(); dut.io.completion.bits.expect(held) }
            dut.io.completion.ready.poke(true.B)
          }
          while (completion.isEmpty && cycles < 20000) { tick(); cycles += 1 }
          completion shouldBe Some((illegal, 0, false))
        }
        def pack(bytes: Seq[Int]): BigInt = bytes.zipWithIndex.foldLeft(BigInt(0)) {
          case (bits, (byte, index)) => bits | (BigInt(byte) << (8 * index))
        }
        def load(reg: Int, bytes: Seq[Int]): Unit = {
          val address = 0x1000 + reg * ep.vlenBytes
          bytes.grouped(ep.mlenBytes).zipWithIndex.foreach { case (line, offset) =>
            memory(BigInt(address + offset * ep.mlenBytes)) = pack(line)
          }
          send(0x10, 0, ep.vlenBytes, reg, base = address)
        }
        def expectGroup(reg: Int, bytes: Seq[Int]): Unit = {
          for ((chunk, index) <- bytes.grouped(ep.dlenBytes).zipWithIndex)
            dut.io.vrf(reg * ep.vlenBits / ep.dlenBits + index).expect(pack(chunk).U)
        }
        val input = (0 until 2 * ep.vlenBytes).map(byte => (byte * 29 + byte / 251 + 7) & 255)
        val sentinel = Seq.fill(ep.vlenBytes)(0xa5)
        for (sew <- 0 to 2; lmul <- Seq(0, 1, 7); down <- Seq(false, true); scenario <- 0 until 12) {
          val width = 1 << sew
          val vlmax = (ep.vlenBytes / width) * (if (lmul == 1) 2 else 1) / (if (lmul == 7) 2 else 1)
          val offsets = Seq(BigInt(0), BigInt(1), BigInt(ep.dlenBytes / width - 1),
            BigInt(ep.dlenBytes / width), BigInt(ep.dlenBytes / width + 1), BigInt(vlmax - 1),
            BigInt(vlmax), BigInt(vlmax + 1), BigInt(1) << 32, (BigInt(1) << 64) - 1, BigInt(1), BigInt(1))
          val offset = offsets(scenario)
          val vl = if (scenario == 10) 0 else if (scenario == 0 || scenario == 11) vlmax else vlmax - 3
          val alias = down && scenario == 11
          val dest = if (alias) 4 else 8
          withClue(s"sew=$sew lmul=$lmul down=$down scenario=$scenario: ") {
            dut.io.lmul.get.poke(0.U)
            load(4, input.take(ep.vlenBytes))
            load(5, input.drop(ep.vlenBytes))
            load(6, sentinel)
            for (reg <- 8 to 10) load(reg, sentinel)
            val expected = (if (alias) input ++ sentinel else sentinel ++ sentinel ++ sentinel).toArray
            for (element <- 0 until vl if down || BigInt(element) >= offset) {
              val sourceIndex = if (down) BigInt(element) + offset else BigInt(element) - offset
              for (byte <- 0 until width)
                expected(element * width + byte) = if (sourceIndex >= vlmax) 0 else input(sourceIndex.toInt * width + byte)
            }
            dut.io.lmul.get.poke(lmul.U)
            send(if (down) 0x3f else 0x3e, sew, vl, dest, base = offset, backpressure = scenario % 3 == 0)
            expectGroup(dest, expected.toSeq)
          }
        }
        for (vl <- Seq(0, 13)) {
          dut.io.lmul.get.poke(0.U)
          for (sew <- 0 to 7) send(0x19, sew, vl, 8, illegal = true, backpressure = true)
          send(0x3e, 2, vl, 4, illegal = true)
          send(0x3f, 3, vl, 8, illegal = true)
          dut.io.lmul.get.poke(1.U)
          for ((dest, source) <- Seq((5, 4), (8, 5), (31, 4), (8, 31)))
            send(0x3f, 2, vl, dest, source = source, illegal = true)
        }
        dut.io.flush.poke(true.B)
        tick()
        dut.io.flush.poke(false.B)
        dut.io.lmul.get.poke(0.U)
        load(4, input.take(ep.vlenBytes))
        load(8, sentinel)
        send(0x3f, 0, 13, 8, base = 1)
        expectGroup(8, input.slice(1, 14) ++ sentinel.drop(13))
      }
  }
}
