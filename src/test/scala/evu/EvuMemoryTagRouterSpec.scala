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
    dut.io.lookupTag.poke(0.U)
    dut.io.memory.req.ready.poke(true.B)
    dut.io.memory.resp.valid.poke(false.B)
    dut.io.memory.resp.bits.data.poke(0.U)
    dut.io.memory.resp.bits.error.poke(false.B)
    dut.io.memory.resp.bits.fault.poke(EvuFault.None)
    dut.io.memory.resp.bits.tag.poke(0.U)
    for (i <- 0 until 2) {
      dut.io.clients(i).req.valid.poke(false.B)
      dut.io.clients(i).req.bits.addr.poke((i * 512).U)
      dut.io.clients(i).req.bits.data.poke(0.U)
      dut.io.clients(i).req.bits.mask.poke(0.U)
      dut.io.clients(i).req.bits.bytes.poke(32.U)
      dut.io.clients(i).req.bits.write.poke(false.B)
      dut.io.clients(i).req.bits.tag.poke(0.U)
      dut.io.clients(i).resp.ready.poke(true.B)
    }
  }

  it should "route identical client-local tags through distinct global IDs and return out of order" in {
    test(new EvuMemoryTagRouter(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      val owners = scala.collection.mutable.Map.empty[Int, (Int, Int)]
      for (i <- 0 until 8) {
        val owner = i % 2
        val local = i / 2
        dut.io.clients(owner).req.valid.poke(true.B)
        dut.io.clients(owner).req.bits.tag.poke(local.U)
        dut.io.clients(owner).req.ready.expect(true.B)
        owners(dut.io.memory.req.bits.tag.peek().litValue.toInt) = (owner, local)
        dut.clock.step()
        dut.io.clients(owner).req.valid.poke(false.B)
      }
      owners.size shouldBe 8
      dut.io.clients(0).req.valid.poke(true.B)
      dut.io.clients(0).req.ready.expect(false.B)
      dut.io.tagStall.expect(true.B)
      dut.io.clients(0).req.valid.poke(false.B)
      for (tag <- owners.keys.toSeq.sorted.reverse) {
        val (owner, local) = owners(tag)
        dut.io.lookupTag.poke(tag.U)
        dut.io.lookupOwner.expect((owner == 1).B)
        dut.io.memory.resp.bits.tag.poke(tag.U)
        dut.io.memory.resp.bits.data.poke((tag + 17).U)
        dut.io.memory.resp.valid.poke(true.B)
        dut.io.clients(owner).resp.valid.expect(true.B)
        dut.io.clients(owner).resp.bits.tag.expect(local.U)
        dut.io.clients(owner).resp.bits.data.expect((tag + 17).U)
        dut.io.clients(1 - owner).resp.valid.expect(false.B)
        dut.io.clients(owner).resp.ready.poke(false.B)
        dut.io.memory.resp.ready.expect(false.B)
        dut.clock.step(2)
        dut.io.clients(owner).resp.ready.poke(true.B)
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
        dut.io.clients(i).req.valid.poke(true.B)
        dut.io.clients(i).req.bits.tag.poke(5.U)
        dut.clock.step()
        dut.io.clients(i).req.valid.poke(false.B)
      }
      dut.io.adapterBusy.poke(true.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.clients(0).req.valid.poke(true.B)
      dut.io.clients(0).req.ready.expect(false.B)
      dut.io.clients(1).resp.valid.expect(false.B)
      dut.io.lookupTag.poke(1.U)
      dut.io.lookupOwner.expect(true.B)
      dut.clock.step(3)
      dut.io.adapterBusy.poke(false.B)
      dut.clock.step() // Discard matrix local ownership.
      dut.io.clients(1).resp.valid.expect(true.B)
      dut.io.clients(1).resp.bits.tag.expect(5.U)
      dut.io.clients(1).resp.bits.error.expect(false.B)
      dut.clock.step(2)
      dut.io.busy.expect(false.B)
      dut.io.clients(0).req.ready.expect(true.B)
    }
  }
}
