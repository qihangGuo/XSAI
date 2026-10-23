package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuControlSequencerSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU control sequencer"

  it should "complete launch and waits, then drain matrix status at fence" in {
    val ep = EvuParams(enable = true, vlenBits = 512, dlenBits = 64, mlenBits = 64, lanes = 1)
    test(new EvuControlSequencer(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.command.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.matrixBusy.poke(true.B)
      dut.io.matrixException.poke(true.B)
      dut.io.matrixFflags.poke(1.U)
      dut.io.jobValid.foreach(_.poke(false.B))
      dut.io.jobState.foreach(_.poke(EvuJobState.Free))

      val cmd = dut.io.command.bits
      cmd.sew.poke(0.U)
      cmd.vd.poke(0.U)
      cmd.vs1.poke(0.U)
      cmd.vs2.poke(0.U)
      cmd.vl.poke(0.U)
      cmd.base.poke(0.U)
      cmd.stride.poke(0.S)
      cmd.length.poke(0.U)
      cmd.aux.poke(0.U)
      cmd.write.poke(false.B)
      cmd.chain.poke(false.B)
      cmd.epoch.poke(0.U)
      cmd.pageMap.enabled.poke(false.B)
      cmd.pageMap.base.poke(0.U)
      cmd.pageMap.length.poke(0.U)
      cmd.pageMap.ppn.foreach(_.poke(0.U))

      def await(signal: Bool): Unit = {
        var cycles = 0
        while (!signal.peek().litToBoolean && cycles < 40) {
          dut.clock.step()
          cycles += 1
        }
        signal.expect(true.B)
      }
      def issue(opcode: UInt, id: Int, target: Int = 0): Unit = {
        cmd.opcode.poke(opcode)
        cmd.jobId.poke(id.U)
        cmd.base.poke(target.U)
        dut.io.command.valid.poke(true.B)
        await(dut.io.command.ready)
        dut.clock.step()
        dut.io.command.valid.poke(false.B)
      }
      def complete(opcode: UInt, drain: Boolean = false): Unit = {
        await(dut.io.completion.valid)
        dut.io.completion.bits.opcode.expect(opcode)
        dut.io.completion.bits.fflags.expect((if (drain) 1 else 0).U)
        dut.io.completion.bits.exception.expect(drain.B)
        dut.io.completion.bits.vxsat.expect(false.B)
        dut.io.completion.bits.dirtyVs.expect((opcode.litValue == EvuOpcode.SetVli.litValue ||
          opcode.litValue == EvuOpcode.SetVl.litValue).B)
        dut.io.completion.bits.cause.expect(if (drain) EvuFault.Access else EvuFault.None)
        dut.clock.step()
      }

      issue(EvuOpcode.Launch, id = 3)
      complete(EvuOpcode.Launch)
      dut.io.jobValid(0).poke(true.B)
      dut.io.jobState(0).poke(EvuJobState.Running)
      issue(EvuOpcode.Wait, id = 0)
      complete(EvuOpcode.Wait)
      dut.io.jobValid(1).poke(true.B)
      dut.io.jobState(1).poke(EvuJobState.Running)
      issue(EvuOpcode.Wait, id = 2, target = 1)
      dut.clock.step(5)
      dut.io.completion.valid.expect(false.B)
      dut.io.jobState(1).poke(EvuJobState.Completed)
      complete(EvuOpcode.Wait)
      issue(EvuOpcode.Fence, id = 4)
      dut.clock.step(5)
      dut.io.completion.valid.expect(false.B)
      dut.io.matrixBusy.poke(false.B)
      complete(EvuOpcode.Fence, drain = true)
      issue(EvuOpcode.SetVli, id = 5)
      complete(EvuOpcode.SetVli)
      issue(EvuOpcode.SetVl, id = 6)
      complete(EvuOpcode.SetVl)
    }
  }

  it should "elaborate the shared Saturn special sequencer without emitting Verilog" in {
    val chirrtl = circt.stage.ChiselStage.emitCHIRRTL(new EvuSpecialSequencer(EvuParams(enable = true)))
    chirrtl should include("module EvuMatrixDequant")
  }
}
