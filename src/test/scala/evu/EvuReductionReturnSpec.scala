package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.backend.ReductionReturnRouter

class EvuReductionReturnSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Independent reduction return contexts"
  it should "route simultaneous and interleaved FU returns by VAT with independent slots and byte masks" in {
    test(new ReductionReturnRouter(5, 9, 256, 2, 3)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.owners(0).valid.poke(true.B)
      dut.io.owners(0).bits.poke(7.U)
      dut.io.owners(1).valid.poke(true.B)
      dut.io.owners(1).bits.poke(12.U)
      dut.io.returns.foreach { r =>
        r.valid.poke(false.B)
        r.bits.vat.poke(0.U)
        r.bits.slot.poke(0.U)
        r.bits.eg.poke(0.U)
        r.bits.mask.poke(0.U)
        r.bits.data.poke(0.U)
      }
      for (step <- 0 until 6) {
        val a = step % 3
        val b = (step + 1) % 3
        dut.io.returns.foreach(_.valid.poke(false.B))
        for ((port, owner, slot, data, mask) <- Seq((a, 7, step % 4, 0x1234, 15),
            (b, 12, (step + 1) % 4, 0x5678, 255))) {
          val r = dut.io.returns(port)
          r.valid.poke(true.B)
          r.bits.vat.poke(owner.U)
          r.bits.slot.poke(slot.U)
          r.bits.data.poke(data.U)
          r.bits.mask.poke(mask.U)
        }
        dut.io.routed(0).valid.expect(true.B)
        dut.io.routed(0).bits.vat.expect(7.U)
        dut.io.routed(0).bits.slot.expect((step % 4).U)
        dut.io.routed(0).bits.data.expect(0x1234.U)
        dut.io.routed(0).bits.mask.expect(15.U)
        dut.io.routed(1).valid.expect(true.B)
        dut.io.routed(1).bits.vat.expect(12.U)
        dut.io.routed(1).bits.data.expect(0x5678.U)
        dut.clock.step()
      }
      dut.io.returns.foreach(_.valid.poke(false.B))
      dut.io.owners.foreach(_.valid.poke(false.B))
      dut.io.routed.foreach(_.valid.expect(false.B))
    }
  }
}
