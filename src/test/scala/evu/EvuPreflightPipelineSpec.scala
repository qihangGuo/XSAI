package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import xiangshan.evu._

class EvuPreflightPipelineSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Pipelined EVU TLB/PMP checks"

  for (replay <- Seq(false, true)) {
    it should s"overlap checks, preserve result order and wrap slots under backpressure (replay=$replay)" in {
      test(new EvuPreflightPipeline).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.cancel.poke(false.B)
        dut.io.translation.request.ready.poke(true.B)
        dut.io.translation.response.valid.poke(false.B)
        dut.io.permissionFault.poke(false.B)
        dut.io.response.ready.poke(true.B)
        val pending = mutable.Queue.empty[(Int, BigInt, Int, Boolean)]
        val attempts = mutable.Map.empty[Int, Int].withDefaultValue(0)
        val grants = mutable.ArrayBuffer.empty[Int]
        var cycle = 0
        var sent = 0
        var returned = 0
        var pmpFault = false
        while (returned < 33 && cycle < 400) {
          dut.io.request.valid.poke((sent < 33).B)
          dut.io.request.bits.vaddr.poke((0x80000000L + sent * 64).U)
          dut.io.request.bits.write.poke((sent % 2 == 1).B)
          dut.io.response.ready.poke((cycle % 9 != 8).B)
          val response = pending.headOption.filter(_._1 == cycle)
          val rsp = dut.io.translation.response
          rsp.valid.poke(response.nonEmpty.B)
          rsp.bits.tag.poke(response.map(_._2).getOrElse(BigInt(0)).U)
          val index = response.map(_._3).getOrElse(0)
          val missed = response.exists(_._4)
          rsp.bits.answer.paddr.poke((0x80000000L + index * 64).U)
          rsp.bits.answer.gpaddr.poke((0x90000000L + index * 64).U)
          rsp.bits.answer.nonLeaf.poke((index == 5).B)
          rsp.bits.answer.fault.poke(if (index == 5) EvuAddressFault.GuestPage else EvuAddressFault.None)
          rsp.bits.miss.poke(missed.B)
          rsp.bits.uncacheable.poke((index == 6).B)
          dut.io.permissionFault.poke(pmpFault.B)
          if (dut.io.request.valid.peek().litToBoolean && dut.io.request.ready.peek().litToBoolean) sent += 1
          val request = dut.io.translation.request
          val grant = if (request.valid.peek().litToBoolean && request.ready.peek().litToBoolean) {
            val tag = request.bits.tag.peek().litValue
            val address = request.bits.check.vaddr.peek().litValue
            val number = ((address - BigInt("80000000", 16)) / 64).toInt
            request.bits.check.write.expect((number % 2 == 1).B)
            attempts(number) += 1
            grants += cycle
            Some((cycle + 1, tag, number, replay && number == 0 && attempts(number) <= 3))
          } else None
          if (dut.io.response.valid.peek().litToBoolean && dut.io.response.ready.peek().litToBoolean) {
            dut.io.response.bits.paddr.expect((0x80000000L + returned * 64).U)
            dut.io.response.bits.fault.expect(if (returned == 5) EvuAddressFault.GuestPage else
              if (returned == 6 || returned == 7) EvuAddressFault.Access else EvuAddressFault.None)
            returned += 1
          }
          dut.clock.step()
          if (response.nonEmpty) pending.dequeue()
          grant.foreach(pending.enqueue(_))
          pmpFault = response.nonEmpty && !missed && index == 7
          cycle += 1
        }
        returned shouldBe 33
        attempts(0) shouldBe (if (replay) 4 else 1)
        if (!replay) grants.take(8).sliding(2).foreach(pair => pair(1) - pair(0) shouldBe 1)
        dut.io.request.valid.poke(false.B)
        dut.io.translation.response.valid.poke(false.B)
        dut.clock.step(3)
        dut.io.busy.expect(false.B)
      }
    }
  }

  for (pmpStage <- Seq(false, true)) {
    it should s"discard cancelled returns before reusing slot IDs (PMP stage=$pmpStage)" in {
      test(new EvuPreflightPipeline).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.cancel.poke(false.B)
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.vaddr.poke("h80000000".U)
        dut.io.request.bits.write.poke(false.B)
        dut.io.response.ready.poke(true.B)
        dut.io.translation.request.ready.poke(true.B)
        dut.io.translation.response.valid.poke(false.B)
        dut.io.permissionFault.poke(false.B)
        dut.io.request.ready.expect(true.B)
        dut.clock.step()
        dut.io.request.valid.poke(false.B)
        dut.io.translation.request.valid.expect(true.B)
        val tag = dut.io.translation.request.bits.tag.peek().litValue
        dut.clock.step()
        dut.io.translation.response.valid.poke(true.B)
        dut.io.translation.response.bits.tag.poke(tag.U)
        dut.io.translation.response.bits.answer.paddr.poke("hdead0000".U)
        dut.io.translation.response.bits.answer.gpaddr.poke(0.U)
        dut.io.translation.response.bits.answer.nonLeaf.poke(false.B)
        dut.io.translation.response.bits.answer.fault.poke(EvuAddressFault.Access)
        dut.io.translation.response.bits.miss.poke(false.B)
        dut.io.translation.response.bits.uncacheable.poke(false.B)
        if (pmpStage) {
          dut.clock.step()
          dut.io.translation.response.valid.poke(false.B)
        }
        dut.io.cancel.poke(true.B)
        dut.clock.step()
        dut.io.cancel.poke(false.B)
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.vaddr.poke("h80000100".U)
        dut.io.permissionFault.poke(true.B)
        for (_ <- 0 until 3) {
          dut.io.request.ready.expect(false.B)
          dut.io.response.valid.expect(false.B)
          dut.clock.step()
          dut.io.translation.response.valid.poke(false.B)
        }
        dut.io.permissionFault.poke(false.B)
        dut.io.request.ready.expect(true.B)
        dut.clock.step()
        dut.io.request.valid.poke(false.B)
        dut.io.translation.request.bits.check.vaddr.expect("h80000100".U)
        val fresh = dut.io.translation.request.bits.tag.peek().litValue
        dut.clock.step()
        dut.io.translation.response.valid.poke(true.B)
        dut.io.translation.response.bits.tag.poke(fresh.U)
        dut.io.translation.response.bits.answer.paddr.poke("h80000100".U)
        dut.io.translation.response.bits.answer.fault.poke(EvuAddressFault.None)
        dut.clock.step()
        dut.io.translation.response.valid.poke(false.B)
        dut.clock.step()
        dut.io.response.valid.expect(true.B)
        dut.io.response.bits.paddr.expect("h80000100".U)
        dut.io.response.bits.fault.expect(EvuAddressFault.None)
      }
    }
  }
}
