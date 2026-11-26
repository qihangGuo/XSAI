package xiangshan.evucheck

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.backend.rob.EvuLoadQueue
import xiangshan.evu.EvuPreflightReasons

class EvuLoadBarrierProbe extends Module {
  val io = IO(new Bundle {
    val reasons = Input(new EvuPreflightReasons)
    val stable = Input(Bool())
    val blocked = Output(Bool())
  })
  io.blocked := EvuPreflightReasons.blocksLoad(io.reasons, io.stable)
}

class EvuLoadQueueSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "capture younger operands first, preserve a stalled head and revoke only killed reservations" in {
    test(new EvuLoadQueue(UInt(32.W), 4)).withAnnotations(Seq(TreadleBackendAnnotation)) { d =>
      val i = d.io
      i.reserve.valid.poke(false.B); i.capture.valid.poke(false.B)
      i.kill.foreach(_.poke(false.B)); i.retire.ready.poke(false.B); i.head.poke(0.U)
      def reserve(tag: Int): Unit = {
        i.full.expect(false.B); i.reserve.bits.poke(tag.U); i.reserve.valid.poke(true.B)
        d.clock.step(); i.reserve.valid.poke(false.B)
      }
      def fill(tag: Int, data: Int): Unit = {
        i.capture.bits.tag.poke(tag.U); i.capture.bits.data.poke(data.U); i.capture.valid.poke(true.B)
        i.capture.ready.expect(true.B); d.clock.step(); i.capture.valid.poke(false.B)
      }
      reserve(7); reserve(8); i.full.expect(true.B)
      i.head.poke(7.U); fill(8, 16); i.retire.valid.expect(false.B)
      fill(7, 64); i.retire.valid.expect(true.B); i.retire.bits.expect(64.U)
      d.clock.step(3); i.retire.bits.expect(64.U)
      i.kill(1).poke(true.B); d.clock.step(); i.kill(1).poke(false.B)
      i.retire.valid.expect(true.B); i.full.expect(false.B)
      reserve(0); i.capture.bits.tag.poke(8.U); i.capture.ready.expect(false.B)
      // Reuse the low index but not the wrap bit. A killed delayed address cannot fill it.
      fill(0, 0); i.retire.ready.poke(true.B); d.clock.step()
      i.head.poke(8.U); i.retire.valid.expect(false.B)
      i.head.poke(0.U); i.retire.valid.expect(true.B); i.retire.bits.expect(0.U)
      d.clock.step(); i.pending.expect(false.B)
      reserve(1); i.kill(0).poke(true.B); d.clock.step(); i.kill(0).poke(false.B)
      i.pending.expect(false.B); i.capture.bits.tag.poke(1.U); i.capture.ready.expect(false.B)
    }
  }

  it should "waive only published control and arithmetic-only drain with a stable config" in {
    test(new EvuLoadBarrierProbe).withAnnotations(Seq(TreadleBackendAnnotation)) { d =>
      val r = d.io.reasons
      for (ctrl <- 0 to 2; status <- 0 to 2; stable <- Seq(false, true); fresh <- Seq(false, true)) {
        r.control.poke((ctrl != 0).B); r.unpublishedControl.poke((ctrl == 2).B)
        r.status.poke((status != 0).B); r.arithmeticOnly.poke((status == 1).B)
        r.otherCompletion.poke(fresh.B); d.io.stable.poke(stable.B)
        d.io.blocked.expect(((ctrl != 0 || status != 0) && (!stable || ctrl == 2 || status == 2 || fresh)).B)
      }
    }
  }
}
