package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuOperandCollectionSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU multi-cycle operand collection"
  private val ep = EvuParams(enable = true, vlenBits = 512)
  private def words(value: Int): BigInt = (0 until 8).map(i => BigInt(value) << (32*i)).reduce(_ | _)
  private def command(bits: EvuCommand, op: UInt, sew: Int = 2): Unit = {
    bits.opcode.poke(op)
    bits.sew.poke(sew.U)
    bits.vd.poke(17.U)
    bits.vs1.poke(1.U)
    bits.vs2.poke(9.U)
    bits.vl.poke(8.U)
    bits.base.poke(0.U)
    bits.stride.poke(0.S)
    bits.length.poke(0.U)
    bits.aux.poke(0.U)
    bits.write.poke(false.B)
    bits.chain.poke(false.B)
    bits.jobId.poke(0.U)
    bits.epoch.poke(0.U)
    bits.pageMap.enabled.poke(false.B)
    bits.pageMap.base.poke(0.U)
    bits.pageMap.length.poke(0.U)
    bits.pageMap.ppn.foreach(_.poke(0.U))
  }
  private def await(dut: Module, ready: Bool): Unit = {
    var cycles = 0
    while (!ready.peek().litToBoolean && cycles < 30) { dut.clock.step(); cycles += 1 }
    ready.expect(true.B)
  }

  it should "retain quant input, scale and bias received in separate cycles" in {
    test(new EvuSpecialSequencer(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.command.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.cuteWrite.ready.poke(false.B)
      dut.io.matrix.in.valid.poke(false.B)
      dut.io.matrix.out.ready.poke(true.B)
      dut.io.vrf.read.foreach(_.ready.poke(false.B))
      dut.io.vrf.readData.foreach(_.poke(0.U))
      command(dut.io.command.bits, EvuOpcode.SetCfg)
      dut.io.command.bits.base.poke(1.U) // zero point
      dut.io.command.bits.vd.poke(0.U) // descriptor 0
      dut.io.command.valid.poke(true.B)
      await(dut, dut.io.command.ready)
      dut.clock.step()
      dut.io.command.valid.poke(false.B)
      await(dut, dut.io.completion.valid)
      dut.clock.step()
      command(dut.io.command.bits, EvuOpcode.QuantToCute)
      dut.io.command.valid.poke(true.B)
      await(dut, dut.io.command.ready)
      dut.clock.step()
      dut.io.command.valid.poke(false.B)
      for ((port, value) <- Seq(2 -> 3, 0 -> 10, 1 -> 2)) {
        await(dut, dut.io.vrf.read(port).valid)
        dut.io.vrf.read(port).ready.poke(true.B)
        dut.io.vrf.readData(port).poke(words(value).U)
        dut.clock.step()
        dut.io.vrf.read(port).ready.poke(false.B)
        dut.io.vrf.readData(port).poke(0.U)
        dut.io.vrf.read(port).valid.expect(false.B)
        dut.io.completion.valid.expect(false.B)
      }
      await(dut, dut.io.cuteWrite.valid)
      dut.io.cuteWrite.bits.data.expect(words(22).U) // clamp((10-1)*2+3) + 1
      dut.io.cuteWrite.bits.byteMask.expect("hffffffff".U)
      dut.clock.step(2)
      dut.io.cuteWrite.valid.expect(true.B)
      dut.io.cuteWrite.bits.data.expect(words(22).U)
      dut.io.completion.valid.expect(false.B)
      dut.io.cuteWrite.ready.poke(true.B)
      dut.clock.step()
      await(dut, dut.io.completion.valid)
      dut.io.completion.bits.exception.expect(false.B)
      dut.io.completion.bits.dirtyVs.expect(false.B)
      dut.clock.step(4)
      dut.io.completion.valid.expect(false.B)

      command(dut.io.command.bits, EvuOpcode.QuantToCute)
      dut.io.command.valid.poke(true.B)
      await(dut, dut.io.command.ready)
      dut.clock.step()
      dut.io.command.valid.poke(false.B)
      dut.io.cuteWrite.ready.poke(false.B)
      dut.io.vrf.read.foreach(_.ready.poke(true.B))
      dut.io.vrf.readData.foreach(_.poke(words(1).U))
      await(dut, dut.io.cuteWrite.valid)
      dut.io.flush.poke(true.B)
      dut.io.cuteWrite.valid.expect(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.cuteWrite.ready.poke(true.B)
      for (_ <- 0 until 4) {
        dut.io.cuteWrite.valid.expect(false.B)
        dut.io.completion.valid.expect(false.B)
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
    }
  }
}
