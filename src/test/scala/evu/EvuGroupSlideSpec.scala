package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common._

class EvuGroupSlideSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val base = Parameters.empty.alterPartial {
    case MonitorsEnabled => false
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }

  private def clear(data: Data): Unit = data match {
    case value: Bool => value.poke(false.B)
    case value: UInt => value.poke(0.U)
    case value: SInt => value.poke(0.S)
    case values: Vec[_] => values.foreach(clear)
    case value: Bundle => value.elements.values.foreach(clear)
  }

  behavior of "Single-group slide bypass"

  for (datapath <- Seq(128, 256, 512)) {
    it should s"shift byte, halfword and word elements with hazards and backpressure at DLEN=$datapath" in {
      implicit val parameters: Parameters = EvuVectorParameters(base, 4096, datapath, datapath,
        VectorParams.lgvParams.copy(alignedSlide = true, singleGroupSlide = true))
      test(LazyModule(new EvuAlignedSlideShell).module)
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          val groups = 4096 / datapath
          val sourceEg = 4 * groups
          val destinationEg = 8 * groups
          val fullMask = (BigInt(1) << datapath) - 1
          val input = (0 until datapath / 8).foldLeft(BigInt(0)) { (result, index) =>
            result | (BigInt((index * 37 + 11) & 255) << (8 * index))
          }
          dut.io.command.valid.poke(false.B)
          dut.io.olderReads.poke(0.U)
          dut.io.olderWrites.poke(0.U)
          dut.io.readReady.poke(false.B)
          dut.io.issueReady.poke(false.B)
          dut.io.writeReady.poke(true.B)
          for (sew <- 0 to 2; up <- Seq(false, true);
               offset <- Seq(1, (datapath / (8 << sew)) / 2, datapath / (8 << sew) - 1).distinct) {
            val elements = datapath / (8 << sew)
            val length = if (up) elements else elements - offset
            val function = if (up) OPIFunct6.slideup else OPIFunct6.slidedown
            clear(dut.io.command.bits)
            val command = dut.io.command.bits
            command.bits.poke(((function.litValue << 26) | (BigInt(1) << 25) |
              (4 << 20) | (1 << 15) | (4 << 12) | (8 << 7) | 0x57).U)
            command.vconfig.vl.poke(length.U)
            command.vconfig.vtype.vsew.poke(sew.U)
            command.rs1_data.poke(offset.U)
            command.wvd.poke(true.B)
            dut.io.specialAccepts.expect(false.B)
            dut.io.command.valid.poke(true.B)
            dut.io.command.ready.expect(true.B)
            dut.clock.step()
            dut.io.command.valid.poke(false.B)
            dut.io.olderWrites.poke((BigInt(1) << sourceEg).U)
            dut.io.readReady.poke(true.B)
            dut.io.readData.poke(input.U)
            dut.io.read.valid.expect(false.B)
            dut.io.accepted.expect(false.B)
            dut.clock.step()
            dut.io.olderWrites.poke(0.U)
            dut.io.readReady.poke(false.B)
            dut.io.read.valid.expect(true.B)
            dut.io.read.bits.expect(sourceEg.U)
            dut.io.accepted.expect(false.B)
            dut.clock.step()
            dut.io.readReady.poke(true.B)
            dut.clock.step()
            dut.io.readReady.poke(false.B)
            dut.io.readData.poke(0.U)
            dut.io.read.valid.expect(false.B)
            dut.io.issueReady.poke(true.B)
            dut.io.olderWrites.poke((BigInt(1) << destinationEg).U)
            dut.io.accepted.expect(false.B)
            dut.clock.step()
            dut.io.olderWrites.poke(0.U)
            dut.io.olderReads.poke((BigInt(1) << destinationEg).U)
            dut.io.accepted.expect(false.B)
            dut.clock.step()
            dut.io.olderReads.poke(0.U)
            dut.io.writeReady.poke(false.B)
            dut.io.accepted.expect(false.B)
            dut.clock.step()
            dut.io.writeReady.poke(true.B)
            dut.io.accepted.expect(true.B)
            dut.io.buffered.expect(false.B)
            dut.clock.step()
            val shift = offset * (8 << sew)
            val expected = if (up) (input << shift) & fullMask else input >> shift
            val mask = if (up) fullMask ^ ((BigInt(1) << shift) - 1)
              else (BigInt(1) << (length * (8 << sew))) - 1
            dut.io.output.valid.expect(true.B)
            dut.io.output.bits.eg.expect(destinationEg.U)
            dut.io.output.bits.data.expect(expected.U)
            dut.io.output.bits.mask.expect(mask.U)
            dut.io.issueReady.poke(false.B)
            dut.clock.step()
            dut.io.output.valid.expect(false.B)
          }
          val elements = datapath / 32
          for ((up, masked, start, offset, length) <- Seq(
              (false, true, 0, BigInt(1), elements - 1),
              (false, false, 1, BigInt(1), elements - 1),
              (false, false, 0, BigInt(1), elements),
              (false, false, 0, BigInt(1) << 63, elements),
              (true, false, 0, BigInt(elements), elements))) {
            clear(dut.io.command.bits)
            val function = if (up) OPIFunct6.slideup else OPIFunct6.slidedown
            val command = dut.io.command.bits
            command.bits.poke(((function.litValue << 26) |
              (if (masked) BigInt(0) else BigInt(1) << 25) |
              (4 << 20) | (1 << 15) | (4 << 12) | (8 << 7) | 0x57).U)
            command.vconfig.vl.poke(length.U)
            command.vconfig.vtype.vsew.poke(2.U)
            command.vstart.poke(start.U)
            command.rs1_data.poke(offset.U)
            dut.io.specialAccepts.expect(true.B)
          }
        }
    }
  }
}
