package evu

import chisel3._
import chiseltest._
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu._

class EvuRetiredOpcodeSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "EVU retired encodings"

  it should "complete with an exception without issuing an HBL2 request" in {
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    // Command routing needs only the smallest legal Saturn configuration.
    val ep = EvuParams(
      enable = true,
      vlenBits = 128,
      dlenBits = 64,
      mlenBits = 64,
      lanes = 1,
      dispatchDepth = 1,
      loadIssueDepth = 1,
      storeIssueDepth = 1,
      executeIssueDepth = 1,
      specialIssueDepth = 1,
      loadInflightDepth = 8,
      loadResponseDepth = 8,
      commandDepth = 4,
      completionDepth = 4
    )
    test(LazyModule(new EvuTopHarness(ep)).module)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.io.dispatch.valid.poke(false.B)
        dut.io.matrixInput.valid.poke(false.B)
        dut.io.matrixOutput.ready.poke(true.B)
        dut.io.completion.ready.poke(false.B)
        dut.io.flush.poke(false.B)
        dut.io.a.ready.poke(true.B)
        dut.io.d.valid.poke(false.B)

        for (funct7 <- Seq(0x09, 0x0b, 0x38, 0x4c, 0x4d, 0x4e, 0x4f); sew <- 0 to 2; vl <- Seq(0, 16)) {
          dut.io.dispatch.bits.instr.poke(((BigInt(funct7) << 25) | (sew << 12) | (3 << 7) | 0x0b).U)
          dut.io.dispatch.bits.base.poke("hfff0".U)
          dut.io.dispatch.bits.vl.poke(vl.U)
          dut.io.dispatch.bits.length.poke(64.U)
          dut.io.dispatch.bits.aux.poke(0.U)
          dut.io.dispatch.bits.stride.poke(0.S)
          dut.io.dispatch.bits.chain.poke(false.B)
          dut.io.dispatch.bits.pageMap.enabled.poke(false.B)
          dut.io.dispatch.bits.pageMap.base.poke(0.U)
          dut.io.dispatch.bits.pageMap.length.poke(0.U)
          dut.io.dispatch.bits.pageMap.ppn.foreach(_.poke(0.U))
          dut.io.dispatch.valid.poke(true.B)
          dut.io.dispatch.ready.expect(true.B)
          dut.clock.step()
          dut.io.dispatch.valid.poke(false.B)

          var cycles = 0
          while (!dut.io.completion.valid.peek().litToBoolean && cycles < 80) {
            dut.io.a.valid.expect(false.B)
            dut.clock.step()
            cycles += 1
          }
          dut.io.completion.valid.expect(true.B)
          dut.io.completion.bits.opcode.expect(EvuOpcode.Invalid)
          dut.io.completion.bits.vd.expect(3.U)
          dut.io.completion.bits.exception.expect(true.B)
          dut.io.completion.bits.dirtyVs.expect(false.B)
          dut.io.completion.bits.cause.expect(EvuFault.Illegal)
          dut.io.a.valid.expect(false.B)
          dut.io.completion.ready.poke(true.B)
          dut.clock.step()
          dut.io.completion.ready.poke(false.B)
        }
      }
  }
}
