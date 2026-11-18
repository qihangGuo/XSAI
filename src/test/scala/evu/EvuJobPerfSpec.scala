package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.EvuJobPerf

class EvuJobPerfSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Ordered-job pressure diagnostics"

  it should "separate head-of-line waits, full-window release and idle/backpressure" in {
    test(new EvuJobPerf(4, diagnostics = true)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.live.foreach(_.poke(false.B))
      dut.io.completed.foreach(_.poke(false.B))
      dut.io.head.poke(3.U)
      dut.io.headValid.poke(false.B)
      dut.io.commandValid.poke(false.B)
      dut.io.hasSlot.poke(true.B)
      dut.io.retiring.poke(false.B)
      dut.clock.step()
      dut.io.full.expect(0.U)
      dut.io.headBlocked.expect(0.U)
      dut.io.live.foreach(_.poke(true.B))
      dut.io.headValid.poke(true.B)
      dut.io.commandValid.poke(true.B)
      dut.io.hasSlot.poke(false.B)
      dut.io.completed(0).poke(true.B) // Younger completed job after circular wrap.
      dut.clock.step(3)
      dut.io.full.expect(3.U)
      dut.io.headBlocked.expect(3.U)
      dut.io.fullRelease.expect(0.U)
      dut.io.completed(3).poke(true.B)
      dut.clock.step(2) // Ready head held by the receiver is not an unfinished head.
      dut.io.headBlocked.expect(3.U)
      dut.io.full.expect(5.U)
      dut.io.retiring.poke(true.B)
      dut.clock.step()
      dut.io.fullRelease.expect(1.U)
      dut.io.hasSlot.poke(true.B)
      dut.clock.step()
      dut.io.full.expect(6.U)
      dut.io.fullRelease.expect(1.U)
      dut.io.completed(3).poke(false.B)
      dut.io.live(0).poke(false.B) // Ignore stale completed bits in free slots.
      dut.io.commandValid.poke(false.B)
      dut.clock.step()
      dut.io.headBlocked.expect(3.U)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.full.expect(0.U)
      dut.io.fullRelease.expect(0.U)
      dut.io.headBlocked.expect(0.U)
    }
  }
}
