package evu

import chisel3._
import chiseltest._
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.tilelink.TLMessages
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

import scala.collection.mutable

class EvuVirtualMemorySpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU virtual memory on HBL2"

  it should "load and store across noncontiguous physical pages through the Saturn datapath" in {
    assume(sys.env.get("EVU_SERVER_RTL_TEST").contains("1"),
      "Full Saturn RTL simulation is reserved for the server; local checks use EvuSaturnAddressSpec")
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    val ep = EvuParams(enable = true, vlenBits = 512)
    test(LazyModule(new EvuTopHarness(ep)).module).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      dut.io.matrixInput.valid.poke(false.B)
      dut.io.matrixOutput.ready.poke(true.B)
      dut.io.completion.ready.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.a.ready.poke(true.B)
      dut.io.d.valid.poke(false.B)
      dut.io.d.bits.param.poke(0.U)
      dut.io.d.bits.size.poke(6.U)
      dut.io.d.bits.sink.poke(0.U)
      dut.io.d.bits.denied.poke(false.B)
      dut.io.d.bits.corrupt.poke(false.B)
      val memory = mutable.Map.empty[BigInt, BigInt].withDefaultValue(0)
      val low = BigInt("0123456789abcdef" * 4, 16)
      val high = BigInt("fedcba9876543210" * 4, 16)
      val halfMask = (BigInt(1) << 256) - 1
      val guards = BigInt("aa" * 64, 16)
      memory(0x3fc0) = low << 256
      memory(0x9000) = high
      memory(0x5fc0) = guards
      memory(0x7000) = guards
      val accesses = mutable.ArrayBuffer.empty[(BigInt, Boolean)]
      var response: Option[(BigInt, BigInt, Boolean)] = None

      def tick(): Unit = {
        dut.io.d.valid.poke(response.nonEmpty.B)
        response.foreach { case (tag, data, write) =>
          dut.io.d.bits.source.poke(tag.U)
          dut.io.d.bits.data.poke(data.U)
          dut.io.d.bits.opcode.poke(if (write) TLMessages.AccessAck else TLMessages.AccessAckData)
        }
        val responseTaken = response.nonEmpty && dut.io.d.ready.peek().litToBoolean
        dut.io.a.ready.poke((response.isEmpty || responseTaken).B)
        val next = if (dut.io.a.valid.peek().litToBoolean && dut.io.a.ready.peek().litToBoolean) {
          val address = dut.io.a.bits.address.peek().litValue
          val tag = dut.io.a.bits.source.peek().litValue
          tag should be < BigInt(ep.maxMemOutstanding)
          val write = dut.io.a.bits.opcode.peek().litValue == TLMessages.PutFullData.litValue
          accesses += ((address, write))
          if (write) memory(address) = dut.io.a.bits.data.peek().litValue
          Some((tag, if (write) BigInt(0) else memory(address), write))
        } else None
        if (response.nonEmpty && !responseTaken) next shouldBe None
        dut.clock.step()
        response = next.orElse(if (responseTaken) None else response)
      }
      def issue(opcode: Int, pages: Seq[Int]): Unit = {
        dut.io.dispatch.bits.instr.poke(((BigInt(opcode) << 25) | (2 << 12) | (1 << 7) | 0x0b).U)
        dut.io.dispatch.bits.base.poke("h1fe0".U)
        dut.io.dispatch.bits.vl.poke(16.U)
        dut.io.dispatch.bits.length.poke(64.U)
        dut.io.dispatch.bits.aux.poke(0.U)
        dut.io.dispatch.bits.stride.poke(0.S)
        dut.io.dispatch.bits.chain.poke(false.B)
        dut.io.dispatch.bits.pageMap.enabled.poke(true.B)
        dut.io.dispatch.bits.pageMap.base.poke("h1fe0".U)
        dut.io.dispatch.bits.pageMap.length.poke(64.U)
        for (i <- 0 until ep.translationPages) dut.io.dispatch.bits.pageMap.ppn(i).poke(pages(i).U)
        dut.io.dispatch.valid.poke(true.B)
        var cycles = 0
        while (!dut.io.dispatch.ready.peek().litToBoolean && cycles < 500) { tick(); cycles += 1 }
        dut.io.dispatch.ready.expect(true.B)
        tick()
        dut.io.dispatch.valid.poke(false.B)
        cycles = 0
        while (!dut.io.completion.valid.peek().litToBoolean && cycles < 1500) { tick(); cycles += 1 }
        dut.io.completion.valid.expect(true.B)
        dut.io.completion.bits.opcode.expect(if (opcode == 0x10) EvuOpcode.Load else
          if (opcode == 0x11) EvuOpcode.Store else EvuOpcode.Invalid)
        dut.io.completion.bits.vd.expect(1.U)
        dut.io.completion.bits.exception.expect((opcode == 0x38).B)
        if (opcode == 0x38) dut.io.completion.bits.cause.expect(EvuFault.Illegal)
        dut.io.completion.ready.poke(true.B)
        tick()
        dut.io.completion.ready.poke(false.B)
      }
      issue(0x38, Seq(3, 9))
      accesses shouldBe empty
      issue(0x10, Seq(3, 9))
      accesses.map(_._1).toSet shouldBe Set(BigInt(0x3fc0), BigInt(0x9000))
      accesses.clear()
      issue(0x11, Seq(5, 7))
      accesses.map(_._1).toSet shouldBe Set(BigInt(0x5fc0), BigInt(0x7000))
      memory(0x5fc0) shouldBe ((guards & halfMask) | (low << 256))
      memory(0x7000) shouldBe ((guards & ~halfMask) | high)
    }
  }
}
