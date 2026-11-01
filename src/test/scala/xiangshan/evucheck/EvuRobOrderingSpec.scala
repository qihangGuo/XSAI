package xiangshan.evucheck

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.backend.decode.{EvuDecoder, XSDecode}
import xiangshan.backend.rob.RobEnqueueBarrier

class EvuRobOrderingSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "ROB backward barriers with asynchronous EVU memory"

  it should "retain non-speculative decode and backward barriers for memory and control" in {
    val entries = EvuDecoder.decodeArray.map { case (pattern, decode) =>
      (pattern.value >> 25).toInt -> decode.asInstanceOf[XSDecode]
    }.toMap
    for (f7 <- Seq(0x10, 0x11, 0x52, 0x53, 0x18, 0x19, 0x1a, 0x45, 0x46)) {
      entries(f7).noSpec shouldBe true
      entries(f7).blockBack shouldBe true
    }
  }

  it should "release ordinary memory after scalar retirement while external work is still busy" in {
    test(new RobEnqueueBarrier).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.robEmpty.poke(true.B)
      dut.io.evuBusy.poke(true.B)
      dut.io.enqueue.valid.poke(false.B)
      dut.io.enqueue.bits.poke(false.B)
      dut.io.blocked.expect(false.B)
      for (_ <- 0 until 2) {
        dut.io.enqueue.valid.poke(true.B)
        dut.io.enqueue.bits.poke(true.B)
        dut.clock.step()
        dut.io.enqueue.valid.poke(false.B)
        dut.io.robEmpty.poke(false.B)
        dut.clock.step(3)
        dut.io.blocked.expect(true.B) // Preflight/scalar retirement still serialize.
        dut.io.robEmpty.poke(true.B)
        dut.clock.step()
        dut.io.blocked.expect(false.B) // No physical Ack is required to enqueue again.
      }
      // WAIT/FENCE/configuration retain the original drain requirement.
      dut.io.enqueue.valid.poke(true.B)
      dut.io.enqueue.bits.poke(false.B)
      dut.clock.step()
      dut.io.enqueue.valid.poke(false.B)
      dut.clock.step(4)
      dut.io.blocked.expect(true.B)
      dut.io.evuBusy.poke(false.B)
      dut.io.robEmpty.poke(false.B)
      dut.clock.step()
      dut.io.blocked.expect(true.B)
      dut.io.robEmpty.poke(true.B)
      dut.clock.step()
      dut.io.blocked.expect(false.B)
    }
  }
}
