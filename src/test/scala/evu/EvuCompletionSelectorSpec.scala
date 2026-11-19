package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.common.CompletionSelector
import xiangshan.evu.backend.WriteCompletionTracker

class EvuCompletionSelectorSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Circular-age completion arbitration"

    it should "select ready tags across wrap and hold a stalled selection" in {
      test(new CompletionSelector(5)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.flush.poke(false.B)
        dut.io.tail.poke(3.U)
        dut.io.pending.poke(((BigInt(1) << 30) | 1).U)
        dut.io.out.ready.poke(false.B)
        val chosen = 30
        dut.io.out.bits.expect(chosen.U)
        dut.clock.step()
        dut.io.pending.poke(((BigInt(1) << 30) | (BigInt(1) << 31) | 3).U)
        dut.io.tail.poke(31.U)
        dut.io.out.bits.expect(chosen.U)
        dut.clock.step(3)
        dut.io.out.bits.expect(chosen.U)
        dut.io.out.ready.poke(true.B)
        dut.clock.step()
        dut.io.pending.poke(3.U)
        dut.io.tail.poke(1.U)
        dut.io.out.bits.expect(1.U)
        dut.io.out.ready.poke(false.B)
        dut.clock.step()
        dut.io.flush.poke(true.B)
        dut.io.out.valid.expect(false.B)
        dut.clock.step()
        dut.io.pending.poke(4.U)
        dut.io.flush.poke(false.B)
        dut.io.out.bits.expect(2.U)
      }
    }

  it should "choose the oldest remaining live tag for every tail and occupancy mask" in {
    test(new CompletionSelector(3)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.out.ready.poke(true.B)
      for (tail <- 0 until 8; mask <- 0 until 256) {
        dut.io.tail.poke(tail.U)
        dut.io.pending.poke(mask.U)
        dut.io.out.valid.expect((mask != 0).B)
        if (mask != 0) {
          val expected = (0 until 8).map(i => (tail + i) % 8).find(i => (mask & (1 << i)) != 0).get
          dut.io.out.bits.expect(expected.U)
        }
      }
    }
  }

  it should "wait for physical writes and distinguish tracker arbitration from write waiting" in {
    test(new WriteCompletionTracker(5, 2, diagnostics = true))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.tail.poke(3.U)
        dut.io.reportHead.get.valid.poke(true.B)
        dut.io.reportHead.get.bits.poke(30.U)
        dut.io.pendingWrites.poke((BigInt(1) << 30).U)
        dut.io.tails(0).valid.poke(true.B)
        dut.io.tails(0).bits.poke(30.U)
        dut.io.tails(1).valid.poke(true.B)
        dut.io.tails(1).bits.poke(0.U)
        dut.clock.step()
        dut.io.tails.foreach(_.valid.poke(false.B))
        dut.io.release.valid.expect(true.B)
        dut.io.release.bits.expect(0.U)
        dut.io.headWriteWait.expect(true.B)
        dut.io.headTrackerWait.expect(false.B)
        dut.clock.step()
        dut.io.pendingWrites.poke(0.U)
        dut.io.headWriteWait.expect(false.B)
        dut.io.release.bits.expect(30.U)
        dut.clock.step()
        dut.io.busy.expect(false.B)
        dut.io.tails(0).valid.poke(true.B)
        dut.io.tails(0).bits.poke(30.U)
        dut.io.tails(1).valid.poke(true.B)
        dut.io.tails(1).bits.poke(0.U)
        dut.io.reportHead.get.bits.poke(0.U)
        dut.clock.step()
        dut.io.tails.foreach(_.valid.poke(false.B))
        dut.io.headTrackerWait.expect(true.B)
        dut.io.release.bits.expect(30.U)
        dut.clock.step()
        dut.io.headTrackerWait.expect(false.B)
        dut.io.release.bits.expect(0.U)
        dut.clock.step()
        dut.io.busy.expect(false.B)
      }
  }
}
