package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuControlSequencerSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU control sequencer"

  it should "complete configuration, but fence only after the backend drains" in {
    val ep = EvuParams(enable = true, vlenBits = 512, dlenBits = 64, mlenBits = 64, lanes = 1)
    test(new EvuControlSequencer(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.command.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.backendBusy.poke(true.B)

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
      def issue(opcode: UInt, id: Int): Unit = {
        cmd.opcode.poke(opcode)
        cmd.jobId.poke(id.U)
        dut.io.command.valid.poke(true.B)
        await(dut.io.command.ready)
        dut.clock.step()
        dut.io.command.valid.poke(false.B)
      }
      def complete(opcode: UInt): Unit = {
        await(dut.io.completion.valid)
        dut.io.completion.bits.opcode.expect(opcode)
        dut.io.completion.bits.fflags.expect(0.U)
        dut.io.completion.bits.exception.expect(false.B)
        dut.io.completion.bits.vxsat.expect(false.B)
        dut.io.completion.bits.dirtyVs.expect((opcode.litValue == EvuOpcode.SetVli.litValue ||
          opcode.litValue == EvuOpcode.SetVl.litValue).B)
        dut.io.completion.bits.cause.expect(EvuFault.None)
        dut.clock.step()
      }

      issue(EvuOpcode.Fence, id = 4)
      dut.clock.step(5)
      dut.io.completion.valid.expect(false.B)
      dut.io.backendBusy.poke(false.B)
      complete(EvuOpcode.Fence)
      issue(EvuOpcode.SetVli, id = 5)
      complete(EvuOpcode.SetVli)
      issue(EvuOpcode.SetVl, id = 6)
      complete(EvuOpcode.SetVl)
    }
  }

}
