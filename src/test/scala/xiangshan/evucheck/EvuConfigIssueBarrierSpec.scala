package xiangshan.evucheck

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.backend.rob.{EvuConfigIssueBarrier, RobPtr}

class EvuConfigIssueProbe extends EvuConfigIssueBarrier(160) {
  val candidate = IO(Input(new RobPtr(160)))
  val evu = IO(Input(Bool()))
  val held = IO(Output(Bool()))
  held := EvuConfigIssueBarrier.holds(io.owner, candidate, evu)
}

class EvuConfigIssueBarrierSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "hold only younger EVU entries across wrap, release on matching publication or owner squash" in {
    test(new EvuConfigIssueProbe) { d =>
      def tag(ptr: RobPtr, flag: Boolean, value: Int): Unit = {
        ptr.flag.poke(flag.B)
        ptr.value.poke(value.U)
      }
      d.io.published.valid.poke(false.B)
      d.io.redirect.valid.poke(false.B)
      d.io.flushSelf.poke(false.B)
      d.evu.poke(true.B)
      def admit(): Unit = {
        tag(d.io.admit.bits, false, 159)
        d.io.admit.valid.poke(true.B)
        d.clock.step()
        d.io.admit.valid.poke(false.B)
        d.io.owner.valid.expect(true.B)
      }
      admit()
      for (older <- Seq(158, 159)) { tag(d.candidate, false, older); d.held.expect(false.B) }
      tag(d.candidate, true, 0)
      d.held.expect(true.B)
      d.evu.poke(false.B); d.held.expect(false.B); d.evu.poke(true.B)
      d.io.redirect.valid.poke(true.B); tag(d.io.redirect.bits, true, 1)
      d.io.published.valid.poke(true.B); d.io.published.bits.poke((256 + 159).U)
      d.clock.step(); d.held.expect(true.B)
      d.io.redirect.valid.poke(false.B)
      d.io.published.bits.poke(159.U); d.clock.step(); d.held.expect(false.B)
      d.io.published.valid.poke(false.B)
      admit()
      d.io.redirect.valid.poke(true.B); tag(d.io.redirect.bits, false, 159)
      d.clock.step(); d.held.expect(true.B)
      d.io.flushSelf.poke(true.B); d.clock.step(); d.held.expect(false.B)
      d.io.redirect.valid.poke(false.B)
      admit()
      tag(d.io.redirect.bits, false, 158); d.io.redirect.valid.poke(true.B)
      d.clock.step(); d.held.expect(false.B)
    }
  }
}
