package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuMemoryTagRouterSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EvuMemoryTagRouter"

  private val ep = EvuParams(enable = true)
  private def initialize(dut: EvuMemoryTagRouter): Unit = {
    dut.io.flush.poke(false.B)
    dut.io.adapterBusy.poke(false.B)
    dut.io.memory.req.ready.poke(true.B)
    dut.io.memory.resp.valid.poke(false.B)
    dut.io.memory.resp.bits.data.poke(0.U)
    dut.io.memory.resp.bits.error.poke(false.B)
    dut.io.memory.resp.bits.fault.poke(EvuFault.None)
    dut.io.memory.resp.bits.tag.poke(0.U)
    dut.io.client.req.valid.poke(false.B)
    dut.io.client.req.bits.addr.poke(0.U)
    dut.io.client.req.bits.data.poke(0.U)
    dut.io.client.req.bits.mask.poke(0.U)
    dut.io.client.req.bits.bytes.poke(64.U)
    dut.io.client.req.bits.fullLineData.poke(false.B)
    dut.io.client.req.bits.write.poke(false.B)
    dut.io.client.req.bits.tag.poke(0.U)
    dut.io.client.req.bits.jobId.poke(0.U)
    dut.io.client.req.bits.epoch.poke(0.U)
    dut.io.client.resp.ready.poke(true.B)
  }

  it should "map all VMU tags onto physical sources and return out of order with backpressure" in {
    test(new EvuMemoryTagRouter(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      val owners = scala.collection.mutable.Map.empty[Int, Int]
      for (i <- 0 until 8) {
        val local = 7 - i
        dut.io.client.req.valid.poke(true.B)
        dut.io.client.req.bits.tag.poke(local.U)
        dut.io.client.req.ready.expect(true.B)
        owners(dut.io.memory.req.bits.tag.peek().litValue.toInt) = local
        dut.clock.step()
        dut.io.client.req.valid.poke(false.B)
      }
      owners.size shouldBe 8
      dut.io.client.req.valid.poke(true.B)
      dut.io.client.req.ready.expect(false.B)
      dut.io.tagStall.expect(true.B)
      dut.io.client.req.valid.poke(false.B)
      for (tag <- owners.keys.toSeq.sorted.reverse) {
        val local = owners(tag)
        dut.io.memory.resp.bits.tag.poke(tag.U)
        dut.io.memory.resp.bits.data.poke((tag + 17).U)
        dut.io.memory.resp.valid.poke(true.B)
        dut.io.client.resp.valid.expect(true.B)
        dut.io.client.resp.bits.tag.expect(local.U)
        dut.io.client.resp.bits.data.expect((tag + 17).U)
        dut.io.client.resp.ready.poke(false.B)
        dut.io.memory.resp.ready.expect(false.B)
        dut.clock.step(2)
        dut.io.client.resp.ready.poke(true.B)
        dut.clock.step()
        dut.io.memory.resp.valid.poke(false.B)
      }
      dut.io.busy.expect(false.B)
    }
  }

  it should "retain owners through flush drain and cancel Saturn tags only after physical traffic drains" in {
    test(new EvuMemoryTagRouter(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      for (i <- 0 until 2) {
        dut.io.client.req.valid.poke(true.B)
        dut.io.client.req.bits.tag.poke((5 + i).U)
        dut.clock.step()
        dut.io.client.req.valid.poke(false.B)
      }
      dut.io.adapterBusy.poke(true.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.client.req.valid.poke(true.B)
      dut.io.client.req.ready.expect(false.B)
      dut.io.client.resp.valid.expect(false.B)
      dut.clock.step(3)
      dut.io.adapterBusy.poke(false.B)
      dut.io.client.resp.valid.expect(true.B)
      dut.io.client.resp.bits.tag.expect(5.U)
      dut.io.client.resp.bits.error.expect(false.B)
      dut.io.client.resp.ready.poke(false.B)
      dut.clock.step(2)
      dut.io.client.resp.bits.tag.expect(5.U)
      dut.io.client.resp.ready.poke(true.B)
      dut.clock.step()
      dut.io.client.resp.bits.tag.expect(6.U)
      dut.clock.step(2)
      dut.io.busy.expect(false.B)
      dut.io.client.req.ready.expect(true.B)
    }
  }
}
