package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.backend.ReductionOwnerAllocator

class EvuReductionOwnerAllocatorSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Reduction owner same-cycle credit"

    it should "preserve new ownership across full-window release" in {
      test(new ReductionOwnerAllocator(2)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.request.poke(true.B)
        dut.io.dispatch.poke(true.B)
        dut.io.release.poke(0.U)
        dut.io.choice.expect(1.U)
        dut.clock.step()
        dut.io.choice.expect(2.U)
        dut.clock.step()
        dut.io.allocated.expect(3.U)
        dut.io.dispatch.poke(false.B)
        dut.io.blocked.expect(true.B)
        dut.io.release.poke(1.U)
        dut.io.fullWithRelease.expect(true.B)
        dut.io.blocked.expect(false.B)
        dut.io.dispatch.poke(true.B)
        dut.io.reused.expect(true.B)
        dut.clock.step()
        dut.io.allocated.expect(3.U)
        dut.io.release.poke(0.U)
        dut.io.dispatch.poke(false.B)
        dut.io.blocked.expect(true.B)
        dut.io.release.poke(3.U)
        dut.io.dispatch.poke(true.B)
        dut.io.request.poke(false.B)
        dut.io.reused.expect(false.B)
        dut.clock.step()
        dut.io.allocated.expect(0.U)
        dut.io.request.poke(true.B)
        dut.io.release.poke(0.U)
        dut.clock.step()
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.dispatch.poke(false.B)
        dut.io.allocated.expect(0.U)
      }
    }

  it should "replace a releasing owner on consecutive cycles without creating duplicate credits" in {
    test(new ReductionOwnerAllocator(2)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.request.poke(true.B)
      dut.io.dispatch.poke(true.B)
      dut.io.release.poke(0.U)
      dut.clock.step(2)
      for (i <- 0 until 32) {
        val released = 1 << (i % 2)
        dut.io.release.poke(released.U)
        dut.io.choice.expect(released.U)
        dut.io.reused.expect(true.B)
        dut.io.blocked.expect(false.B)
        dut.clock.step()
        dut.io.allocated.expect(3.U)
      }
    }
  }
}
