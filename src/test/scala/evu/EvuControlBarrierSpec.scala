package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu._

class EvuControlBarrierSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "EVU WAIT/FENCE dispatch barrier"

  it should "block younger work until completion acceptance and cancel the barrier on flush" in {
    val ep = EvuParams(enable = true)
    test(new EvuControlSequencer(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.command.valid.poke(false.B)
      dut.io.completion.ready.poke(false.B)
      dut.io.matrixBusy.poke(true.B)
      dut.io.matrixException.poke(false.B)
      dut.io.matrixFflags.poke(0.U)
      dut.io.jobValid.foreach(_.poke(false.B))
      dut.io.jobState.foreach(_.poke(0.U))
      val c = dut.io.command.bits
      c.pc.poke(0.U)
      c.vd.poke(0.U)
      c.base.poke(0.U)
      c.jobId.poke(1.U)
      c.epoch.poke(0.U)
      for (opcode <- Seq(EvuOpcode.Fence, EvuOpcode.Wait)) {
        c.opcode.poke(opcode)
        dut.io.jobValid(0).poke(true.B)
        dut.io.jobState(0).poke(0.U)
        dut.io.matrixBusy.poke(true.B)
        dut.io.blockYounger.expect(false.B)
        dut.io.command.valid.poke(true.B)
        dut.io.command.ready.expect(true.B)
        dut.clock.step()
        dut.io.command.valid.poke(false.B)
        dut.io.blockYounger.expect(true.B)
        dut.io.completion.valid.expect(false.B)
        dut.clock.step(3)
        dut.io.matrixBusy.poke(false.B)
        dut.io.jobState(0).poke(3.U)
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
      dut.io.matrixBusy.poke(true.B)
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
