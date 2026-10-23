package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu._

class EvuMemoryConfigSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "EVU memory preflight configuration"

  it should "use accepted vtype and VL while retaining the launch descriptor length" in {
    val ep = EvuParams(enable = true)
    test(new EvuMemoryConfig(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      for (command <- Seq(dut.io.command, dut.io.accepted.bits)) {
        command.instr.poke(((BigInt(0x10) << 25) | (2 << 12) | 0x0b).U)
        command.base.poke(0.U)
        command.vl.poke(128.U)
        command.length.poke(512.U)
        command.aux.poke(0.U)
        command.pageMap.enabled.poke(false.B)
        command.pageMap.base.poke(0.U)
        command.pageMap.length.poke(0.U)
        command.pageMap.ppn.foreach(_.poke(0.U))
      }
      dut.io.accepted.valid.poke(false.B)
      dut.io.length.expect(512.U)
      dut.io.alignment.expect(2.U)
      def configure(vl: Int, aux: Int): Unit = {
        dut.io.accepted.bits.instr.poke(((BigInt(0x45) << 25) | 0x0b).U)
        dut.io.accepted.bits.vl.poke(vl.U)
        dut.io.accepted.bits.aux.poke(aux.U)
        dut.io.accepted.valid.poke(true.B)
        dut.clock.step()
        dut.io.accepted.valid.poke(false.B)
      }
      configure(3, 1 << 3)
      dut.io.length.expect(6.U)
      dut.io.alignment.expect(1.U)
      dut.io.illegal.expect(false.B)
      dut.io.accepted.bits.vl.poke(0.U)
      dut.clock.step(2) // Unaccepted configuration must have no effect.
      dut.io.length.expect(6.U)
      configure(4096, 2 << 3)
      dut.io.length.expect(512.U) // Clamp AVL to VLMAX.
      configure(0, 2 << 3)
      dut.io.length.expect(0.U)
      dut.io.command.instr.poke(((BigInt(0x18) << 25) | (4 << 12) | 0x0b).U)
      dut.io.length.expect(512.U)
      configure(8, 7 << 3)
      dut.io.illegal.expect(true.B)
    }
  }
}
