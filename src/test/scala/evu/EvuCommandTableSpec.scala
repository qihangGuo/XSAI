package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuCommandTableSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Unified EVU command records"

  private val ep = EvuParams(enable = true)

  private def initialize(dut: EvuCommandTable): Unit = {
    dut.io.allocate.valid.poke(false.B)
    dut.io.allocate.bits.pc.poke(0.U)
    dut.io.allocate.bits.opcode.poke(EvuOpcode.Add)
    dut.io.allocate.bits.vd.poke(0.U)
    dut.io.allocate.bits.epoch.poke(0.U)
    dut.io.result.valid.poke(false.B)
    dut.io.result.bits.jobId.poke(0.U)
    dut.io.result.bits.epoch.poke(0.U)
    dut.io.result.bits.outcome.tval.poke(0.U)
    dut.io.result.bits.outcome.tvalValid.poke(false.B)
    dut.io.result.bits.outcome.exception.poke(false.B)
    dut.io.result.bits.outcome.fflags.poke(0.U)
    dut.io.result.bits.outcome.vxsat.poke(false.B)
    dut.io.result.bits.outcome.dirtyVs.poke(true.B)
    dut.io.result.bits.outcome.cause.poke(EvuFault.None)
    dut.io.completion.ready.poke(false.B)
    dut.io.scheduleRelease.poke(0.U)
    dut.io.flush.poke(false.B)
  }

  private def allocate(dut: EvuCommandTable, index: Int, epoch: Int = 0): Unit = {
    dut.io.allocate.valid.poke(true.B)
    dut.io.allocate.bits.pc.poke((0x80000000L + index * 4L).U)
    dut.io.allocate.bits.vd.poke((index % 32).U)
    dut.io.allocate.bits.epoch.poke(epoch.U)
    dut.io.allocate.ready.expect(true.B)
    dut.io.nextId.expect((index % ep.commandDepth).U)
    dut.clock.step()
    dut.io.allocate.valid.poke(false.B)
  }

  private def result(dut: EvuCommandTable, index: Int, epoch: Int = 0): Unit = {
    dut.io.result.valid.poke(true.B)
    dut.io.result.bits.jobId.poke((index % ep.commandDepth).U)
    dut.io.result.bits.epoch.poke(epoch.U)
    dut.io.result.bits.outcome.fflags.poke((index % 32).U)
    dut.io.result.ready.expect(true.B)
    dut.clock.step()
    dut.io.result.valid.poke(false.B)
  }

  it should "hold 32 commands, accept reversed results and report each original identity in order" in {
    test(new EvuCommandTable(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      for (i <- 0 until 32) { allocate(dut, i) }
      dut.io.count.expect(32.U)
      dut.io.allocate.ready.expect(false.B)
      for (i <- 31 to 1 by -1) { result(dut, i) }
      dut.io.completion.valid.expect(false.B)
      result(dut, 0)
      for (i <- 0 until 32) {
        dut.io.completion.valid.expect(true.B)
        dut.io.completion.bits.jobId.expect(i.U)
        dut.io.completion.bits.pc.expect((0x80000000L + i * 4L).U)
        dut.io.completion.bits.vd.expect(i.U)
        dut.io.completion.bits.fflags.expect(i.U)
        dut.io.completion.ready.poke(true.B)
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
      dut.io.count.expect(0.U)
    }
  }

  it should "replace each row of a full window in the same cycle without clearing its new owner" in {
    test(new EvuCommandTable(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      for (i <- 0 until 32) { allocate(dut, i) }
      for (i <- 0 until 32) { result(dut, i) }
      dut.io.completion.ready.poke(true.B)
      for (i <- 32 until 64) {
        dut.io.completion.bits.pc.expect((0x80000000L + (i - 32) * 4L).U)
        allocate(dut, i)
        dut.io.count.expect(32.U)
        dut.io.live(i % 32).expect(true.B)
        dut.io.completed(i % 32).expect(false.B)
        dut.io.state(i % 32).expect(EvuJobState.Accepted)
      }
      dut.io.completion.valid.expect(false.B)
      for (i <- 63 to 32 by -1) { result(dut, i) }
      // No consumer can see a new row until its own outcome has been recorded.
      for (i <- 32 until 64) {
        dut.io.completion.valid.expect(true.B)
        dut.io.completion.bits.pc.expect((0x80000000L + i * 4L).U)
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
    }
  }

  it should "release internal oldest priority before a store Ack or CPU reporting" in {
    test(new EvuCommandTable(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      dut.io.allocate.bits.opcode.poke(EvuOpcode.Store)
      allocate(dut, 0)
      dut.io.allocate.bits.opcode.poke(EvuOpcode.Add)
      allocate(dut, 1)
      dut.io.scheduleHead.expect(0.U)
      dut.io.scheduleRelease.poke(1.U)
      dut.clock.step()
      dut.io.scheduleRelease.poke(0.U)
      dut.io.scheduleHead.expect(1.U)
      dut.io.live(0).expect(true.B)
      dut.io.completed(0).expect(false.B)
      result(dut, 1)
      dut.io.scheduleHead.expect(2.U)
      dut.io.completion.valid.expect(false.B)
      result(dut, 0)
      dut.io.completion.bits.opcode.expect(EvuOpcode.Store)
      dut.io.completion.bits.dirtyVs.expect(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.clock.step()
      dut.io.completion.bits.opcode.expect(EvuOpcode.Add)
      dut.io.completion.bits.dirtyVs.expect(true.B)
    }
  }

  it should "keep a stalled completion stable while younger errors and flags arrive" in {
    test(new EvuCommandTable(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      allocate(dut, 0)
      allocate(dut, 1)
      result(dut, 0)
      val oldCompletion = dut.io.completion.bits.peek().litValue
      dut.io.result.bits.outcome.exception.poke(true.B)
      dut.io.result.bits.outcome.tvalValid.poke(true.B)
      dut.io.result.bits.outcome.tval.poke("hffffffff80004000".U)
      dut.io.result.bits.outcome.cause.poke(EvuFault.Bus)
      dut.io.result.bits.outcome.vxsat.poke(true.B)
      result(dut, 1)
      dut.io.completion.bits.peek().litValue shouldBe oldCompletion
      dut.clock.step(3)
      dut.io.completion.bits.peek().litValue shouldBe oldCompletion
      dut.io.completion.ready.poke(true.B)
      dut.clock.step()
      dut.io.completion.bits.exception.expect(true.B)
      dut.io.completion.bits.tval.expect("hffffffff80004000".U)
      dut.io.completion.bits.cause.expect(EvuFault.Bus)
      dut.io.completion.bits.fflags.expect(1.U)
      dut.io.completion.bits.vxsat.expect(true.B)
    }
  }

  it should "flush pending rows and discard an old-epoch response after numbering restarts" in {
    test(new EvuCommandTable(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      allocate(dut, 0)
      result(dut, 0)
      dut.io.flush.poke(true.B)
      dut.io.allocate.valid.poke(true.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.allocate.ready.expect(false.B)
      dut.io.completion.valid.expect(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.allocate.valid.poke(false.B)
      dut.io.completion.ready.poke(false.B)
      dut.io.busy.expect(false.B)
      allocate(dut, 0, epoch = 1)
      result(dut, 0, epoch = 0)
      dut.io.completed(0).expect(false.B)
      dut.io.completion.valid.expect(false.B)
      result(dut, 0, epoch = 1)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.epoch.expect(1.U)
    }
  }

  it should "use one registered result handoff and support consecutive ordered completions" in {
    test(new EvuCommandTable(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      initialize(dut)
      for (i <- 0 until 8) { allocate(dut, i) }
      dut.io.completion.ready.poke(true.B)
      for (i <- 0 until 8) {
        if (i == 0) { dut.io.completion.valid.expect(false.B) }
        else {
          dut.io.completion.valid.expect(true.B)
          dut.io.completion.bits.jobId.expect((i - 1).U)
        }
        result(dut, i)
      }
      dut.io.completion.bits.jobId.expect(7.U)
      dut.clock.step()
      dut.io.busy.expect(false.B)
    }
  }
}
