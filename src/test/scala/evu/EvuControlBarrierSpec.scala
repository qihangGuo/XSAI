package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu._

class EvuControlBarrierSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "EVU FENCE dispatch barrier"

  it should "block younger work until completion acceptance and cancel the barrier on flush" in {
    val ep = EvuParams(enable = true)
    test(new EvuControlSequencer(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.command.valid.poke(false.B)
      dut.io.completion.ready.poke(false.B)
      dut.io.backendBusy.poke(true.B)
      val c = dut.io.command.bits
      c.pc.poke(0.U)
      c.vd.poke(0.U)
      c.base.poke(0.U)
      c.jobId.poke(1.U)
      c.epoch.poke(0.U)
      for (_ <- 0 until 2) {
        c.opcode.poke(EvuOpcode.Fence)
        dut.io.backendBusy.poke(true.B)
        dut.io.blockYounger.expect(false.B)
        dut.io.command.valid.poke(true.B)
        dut.io.command.ready.expect(true.B)
        dut.clock.step()
        dut.io.command.valid.poke(false.B)
        dut.io.blockYounger.expect(true.B)
        dut.io.completion.valid.expect(false.B)
        dut.clock.step(3)
        dut.io.backendBusy.poke(false.B)
        dut.io.completion.valid.expect(true.B)
        dut.io.blockYounger.expect(true.B)
        dut.clock.step(3)
        dut.io.blockYounger.expect(true.B)
        dut.io.completion.ready.poke(true.B)
        dut.clock.step()
        dut.io.completion.ready.poke(false.B)
        dut.io.blockYounger.expect(false.B)
      }
      c.opcode.poke(EvuOpcode.Fence)
      dut.io.backendBusy.poke(true.B)
      dut.io.command.valid.poke(true.B)
      dut.clock.step()
      dut.io.command.valid.poke(false.B)
      dut.io.blockYounger.expect(true.B)
      dut.io.flush.poke(true.B)
      dut.io.completion.valid.expect(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.blockYounger.expect(false.B)
      dut.io.command.ready.expect(true.B)
    }
  }
}
