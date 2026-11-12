package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuVmuRequestArbiterSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "VMU write arbitration"

  for (pressure <- Seq(false, true)) {
    it should s"bound load grants under window pressure=$pressure without counting backpressure" in {
      test(new EvuVmuRequestArbiter).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.loadPending.poke(true.B)
        dut.io.storePending.poke(true.B)
        dut.io.windowPressure.poke(pressure.B)
        dut.io.grant.poke(false.B)
        dut.io.flush.poke(false.B)
        val limit = if (pressure) 2 else 8
        for (_ <- 0 until 3) {
          for (_ <- 0 until limit) {
            dut.io.chooseStore.expect(false.B)
            dut.clock.step(4)
            dut.io.chooseStore.expect(false.B)
            dut.io.grant.poke(true.B)
            dut.clock.step()
            dut.io.grant.poke(false.B)
          }
          dut.io.chooseStore.expect(true.B)
          dut.clock.step(5)
          dut.io.chooseStore.expect(true.B)
          dut.io.grant.poke(true.B)
          dut.clock.step()
          dut.io.grant.poke(false.B)
          dut.io.chooseStore.expect(false.B)
        }
        dut.io.loadPending.poke(false.B)
        dut.io.chooseStore.expect(true.B)
        dut.io.storePending.poke(false.B)
        dut.io.chooseStore.expect(false.B)
      }
    }
  }

  it should "apply pressure to an already waiting store and reset its budget on flush" in {
    test(new EvuVmuRequestArbiter).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.loadPending.poke(true.B)
      dut.io.storePending.poke(true.B)
      dut.io.windowPressure.poke(false.B)
      dut.io.grant.poke(true.B)
      dut.io.flush.poke(false.B)
      dut.clock.step(2)
      dut.io.grant.poke(false.B)
      dut.io.chooseStore.expect(false.B)
      dut.io.windowPressure.poke(true.B)
      dut.io.chooseStore.expect(true.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.chooseStore.expect(false.B)
      dut.io.grant.poke(true.B)
      dut.clock.step(2)
      dut.io.grant.poke(false.B)
      dut.io.chooseStore.expect(true.B)
      dut.io.storePending.poke(false.B)
      dut.clock.step()
      dut.io.storePending.poke(true.B)
      dut.io.chooseStore.expect(false.B)
    }
  }

  it should "prioritize ready RMW Puts while serving every regular and prepared entry fairly" in {
    val ep = EvuParams(enable = true)
    test(new EvuVmuLineArbiter(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.out.ready.poke(false.B)
      for (i <- 0 until ep.maxMemOutstanding) {
        dut.io.in(i).valid.poke((i < 4).B)
        dut.io.prepared(i).poke((i < 2).B)
        val r = dut.io.in(i).bits
        r.jobId.poke(i.U)
        r.epoch.poke(1.U)
        r.addr.poke((64 * i).U)
        r.data.poke((i + 1).U)
        r.mask.poke(((BigInt(1) << 64) - 1).U)
        r.fullLineData.poke((i < 2).B)
        r.bytes.poke(64.U)
        r.write.poke((i < 2).B)
        r.tag.poke(i.U)
      }
      val grants = Array.fill(4)(0)
      var urgentStreak = 0
      for (_ <- 0 until 18) {
        dut.io.out.valid.expect(true.B)
        val selected = dut.io.chosen.peek().litValue.toInt
        // Backpressure must not spend the priority budget or move either RR.
        dut.clock.step(3)
        dut.io.chosen.expect(selected.U)
        dut.io.out.bits.tag.expect(selected.U)
        dut.io.out.bits.addr.expect((64 * selected).U)
        dut.io.out.ready.poke(true.B)
        for (i <- 0 until ep.maxMemOutstanding) {
          dut.io.in(i).ready.expect((i == selected).B)
        }
        grants(selected) += 1
        urgentStreak = if (selected < 2) urgentStreak + 1 else 0
        urgentStreak should be <= 2
        dut.clock.step()
        dut.io.out.ready.poke(false.B)
      }
      grants.toSeq shouldBe Seq(6, 6, 3, 3)
      dut.io.flush.poke(true.B)
      dut.io.out.valid.expect(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.out.bits.write.expect(true.B)
      // A single class must run without artificial priority bubbles.
      for (i <- 2 until 4) { dut.io.in(i).valid.poke(false.B) }
      dut.io.out.ready.poke(true.B)
      for (_ <- 0 until 8) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.write.expect(true.B)
        dut.clock.step()
      }
      for (i <- 0 until 4) { dut.io.in(i).valid.poke((i >= 2).B) }
      for (_ <- 0 until 8) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.write.expect(false.B)
        dut.clock.step()
      }
    }
  }
}
