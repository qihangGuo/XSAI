package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.TileVisibilityNodeKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.InstructionPrefetch
import xiangshan.evu.common._
import xiangshan.evu.exu.PermuteUnitFactory

class EvuSlidePrefetchShell(implicit p: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("slide-prefetch-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuSlidePrefetchShellImp(this)
}

class EvuSlidePrefetchShellImp(outer: EvuSlidePrefetchShell)(implicit p: Parameters)
    extends LazyModuleImp(outer) {
  val insns = VXSequencerParams("int", Seq(PermuteUnitFactory)).insns
  val prefetch = Module(new InstructionPrefetch(insns, 2)(p.alterPartial {
    case TileVisibilityNodeKey => outer.client
  }))
  val io = IO(chiselTypeOf(prefetch.io))
  io <> prefetch.io
}

class EvuSlidePrefetchSpec extends AnyFlatSpec with ChiselScalatestTester {
  private def clear(data: Data): Unit = data match {
    case b: Bool => b.poke(false.B)
    case u: UInt => u.poke(0.U)
    case s: SInt => s.poke(0.S)
    case v: Vec[_] => v.foreach(clear)
    case b: Bundle => b.elements.values.foreach(clear)
  }

  behavior of "Direct-slide instruction prefetch"

  for (enabled <- Seq(false, true)) {
    it should s"select the first real source group and retain age guards (enabled=$enabled)" in {
      implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
        case MonitorsEnabled => false
        case freechips.rocketchip.tile.XLen => 64
        case freechips.rocketchip.tile.MaxHartIdBits => 1
        case freechips.rocketchip.rocket.PgLevels => 3
      }, 4096, 512, 512, VectorParams.lgvParams.copy(vrfBanking = 4,
        crossInstructionPrefetch = true, slidePrefetch = enabled,
        alignedSlide = true, singleGroupSlide = true))
      test(LazyModule(new EvuSlidePrefetchShell).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        clear(dut.io.next)
        clear(dut.io.writers)
        dut.io.accepts.poke(true.B)
        dut.io.atTail.poke(true.B)
        dut.io.vatHead.poke(2.U)
        dut.io.vatTail.poke(7.U)
        def command(function: BigInt, sew: Int, offset: Int, vl: Int,
            immediate: Boolean = false, masked: Boolean = false): Unit = {
          clear(dut.io.next)
          val f3 = if (immediate) 3 else 4
          val rs1 = if (immediate) offset else 1
          dut.io.next.valid.poke(true.B)
          dut.io.next.bits.bits.poke(((function << 26) | (if (masked) 0 else BigInt(1) << 25) |
            (BigInt(8) << 20) | (BigInt(rs1) << 15) | (BigInt(f3) << 12) |
            (BigInt(16) << 7) | 0x57).U)
          dut.io.next.bits.rs1_data.poke(offset.U)
          dut.io.next.bits.renv2.poke(true.B)
          dut.io.next.bits.vconfig.vl.poke(vl.U)
          dut.io.next.bits.vconfig.vtype.vsew.poke(sew.U)
          dut.io.next.bits.vat.poke(5.U)
        }
        def expectSource(eg: Int, valid: Boolean): Unit = {
          dut.io.hints(0).valid.expect(false.B)
          dut.io.hints(1).valid.expect(valid.B)
          dut.io.hints(2).valid.expect(false.B)
          if (valid) dut.io.hints(1).bits.eg.expect(eg.U)
          dut.io.slide.expect(valid.B)
        }
        for (sew <- 0 to 2) {
          val elements = 64 >> sew
          for (offsetGroups <- Seq(0, 1, 3, 7)) {
            command(OPIFunct6.slidedown.litValue, sew, offsetGroups * elements, elements)
            expectSource(64 + offsetGroups, enabled)
          }
          command(OPIFunct6.slideup.litValue, sew, elements, 4 * elements)
          expectSource(64, enabled)
          command(OPIFunct6.slideup.litValue, sew, 2, elements, immediate = true)
          expectSource(64, enabled)
          command(OPIFunct6.slidedown.litValue, sew, 2, elements - 2, immediate = true)
          expectSource(64, enabled)
        }
        command(OPIFunct6.slidedown.litValue, 2, 32, 16)
        dut.io.writers(0).valid.poke(true.B)
        dut.io.writers(0).bits.vat.poke(3.U)
        dut.io.writers(0).bits.writes.poke((BigInt(1) << 66).U)
        expectSource(66, false)
        dut.io.writers(0).bits.vat.poke(6.U)
        expectSource(66, enabled)
        dut.io.writers(0).bits.vat.poke(3.U)
        dut.io.writers(0).bits.writes.poke((BigInt(1) << 64).U)
        expectSource(66, enabled) // An older writer to E0 must not block an EG2 read.
        dut.io.vatTail.poke(3.U)
        dut.io.next.bits.vat.poke(1.U)
        dut.io.writers(0).bits.vat.poke(31.U)
        dut.io.writers(0).bits.writes.poke((BigInt(1) << 66).U)
        expectSource(66, false)
        dut.io.writers(0).valid.poke(false.B)
        dut.io.atTail.poke(false.B)
        expectSource(66, false)
        dut.io.atTail.poke(true.B)
        dut.io.accepts.poke(false.B)
        expectSource(66, false)
        dut.io.accepts.poke(true.B)
        command(OPIFunct6.slidedown.litValue, 2, 2, 16, immediate = true)
        dut.io.hints(1).valid.expect(false.B) // Spans groups without being aligned.
        command(OPIFunct6.slidedown.litValue, 2, 128, 16)
        dut.io.hints(1).valid.expect(false.B)
        command(OPIFunct6.slideup.litValue, 2, 2, 16, immediate = true, masked = true)
        dut.io.hints(1).valid.expect(false.B)
        command(OPIFunct6.slideup.litValue, 2, 2, 16, immediate = true)
        dut.io.next.bits.vstart.poke(1.U)
        dut.io.hints(1).valid.expect(false.B)
        command(OPIFunct6.slideup.litValue, 2, 2, 0, immediate = true)
        dut.io.hints(1).valid.expect(false.B)
        command(OPIFunct6.rgather.litValue, 2, 17, 16, immediate = true)
        dut.io.hints(1).valid.expect(true.B)
        dut.io.hints(1).bits.eg.expect(65.U)
        dut.io.slide.expect(false.B)
      }
    }
  }
}
