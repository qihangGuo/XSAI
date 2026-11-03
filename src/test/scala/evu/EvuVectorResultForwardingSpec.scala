package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.VectorResultForwarding
import xiangshan.evu.common.VectorParams

class EvuVectorResultForwardingSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Full-EG result forwarding"

  private def parameters: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 4096, 512, 512, VectorParams.lgvParams)

  it should "fall back to the VRF when no FP source is configured" in {
    implicit val p: Parameters = parameters
    test(new VectorResultForwarding(0, 1, 0)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.tail.poke(0.U)
      dut.io.reads(0).valid.poke(true.B)
      dut.io.reads(0).bits.eg.poke(0.U)
      dut.io.reads(0).bits.vat.poke(1.U)
      dut.io.out(0).valid.expect(false.B)
      dut.io.newerWriter(0).expect(false.B)
    }
  }

  it should "select the nearest older producer across VAT wrap and reject partial or superseded results" in {
    implicit val p: Parameters = parameters
    test(new VectorResultForwarding(3, 2, 4)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val mask = (BigInt(1) << 512) - 1
      dut.io.tail.poke(28.U)
      dut.io.results.foreach { r =>
        r.valid.poke(false.B)
        r.bits.eg.poke(10.U)
        r.bits.vat.poke(30.U)
        r.bits.mask.poke(mask.U)
        r.bits.data.poke(0.U)
      }
      dut.io.writers.foreach { w =>
        w.valid.poke(false.B)
        w.bits.vat.poke(0.U)
        w.bits.writes.poke((BigInt(1) << 10).U)
      }
      dut.io.reads.foreach { r =>
        r.valid.poke(true.B)
        r.bits.eg.poke(10.U)
        r.bits.vat.poke(2.U)
      }
      dut.io.results(0).valid.poke(true.B)
      dut.io.results(0).bits.data.poke("h1234".U)
      dut.io.out(0).valid.expect(true.B)
      dut.io.out(0).bits.expect("h1234".U)
      dut.io.results(1).valid.poke(true.B)
      dut.io.results(1).bits.vat.poke(31.U)
      dut.io.results(1).bits.data.poke("h5678".U)
      dut.io.out(0).bits.expect("h5678".U)
      dut.io.writers(0).valid.poke(true.B)
      dut.io.out(0).valid.expect(false.B)
      dut.io.newerWriter(0).expect(true.B)
      dut.io.writers(0).bits.vat.poke(29.U)
      dut.io.out(0).valid.expect(true.B)
      dut.io.writers(0).bits.vat.poke(3.U)
      dut.io.out(0).valid.expect(true.B)
      dut.io.results(1).bits.mask.poke((mask - 1).U)
      dut.io.writers(0).bits.vat.poke(31.U)
      dut.io.out(0).valid.expect(false.B)
      dut.io.writers(0).valid.poke(false.B)
      dut.io.out(0).bits.expect("h1234".U)
      dut.io.reads(1).bits.eg.poke(11.U)
      dut.io.out(1).valid.expect(false.B)
      dut.io.results(0).bits.vat.poke(2.U)
      dut.io.out(0).valid.expect(false.B)
      dut.io.results(0).bits.vat.poke(30.U)
      dut.reset.poke(true.B)
      dut.io.out(0).valid.expect(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.reads(0).valid.poke(false.B)
      dut.io.out(0).valid.expect(false.B)
    }
  }
}
