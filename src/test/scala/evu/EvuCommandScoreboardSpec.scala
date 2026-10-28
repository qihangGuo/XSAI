package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuCommandScoreboardSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Production EVU cross-command reservations"

  it should "overlap independent compute and one memory owner without relaxing RAW WAR WAW or matrix reads" in {
    test(new EvuCommandScoreboard(EvuParams(enable = true)))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.commandValid.poke(false.B)
        dut.io.readMask.poke(0.U)
        dut.io.writeMask.poke(0.U)
        dut.io.matrixReadMask.poke(0.U)
        dut.io.isMemory.poke(false.B)
        dut.io.chainable.poke(false.B)
        dut.io.jobId.poke(0.U)
        dut.io.accept.poke(false.B)
        dut.io.release.valid.poke(false.B)
        dut.io.release.bits.poke(0.U)
        dut.io.flush.poke(false.B)
        def query(read: BigInt = 0, write: BigInt = 0, memory: Boolean = false): Unit = {
          dut.io.commandValid.poke(true.B)
          dut.io.readMask.poke(read.U)
          dut.io.writeMask.poke(write.U)
          dut.io.isMemory.poke(memory.B)
        }
        def accept(job: Int): Unit = {
          dut.io.allowed.expect(true.B)
          dut.io.jobId.poke(job.U)
          dut.io.accept.poke(true.B)
          dut.clock.step()
          dut.io.accept.poke(false.B)
          dut.io.commandValid.poke(false.B)
        }
        def release(job: Int): Unit = {
          dut.io.release.valid.poke(true.B)
          dut.io.release.bits.poke(job.U)
          dut.clock.step()
          dut.io.release.valid.poke(false.B)
        }
        val loadDest = BigInt(1) << 8
        query(write = loadDest, memory = true); accept(0)
        dut.io.busyWriteMask.expect(loadDest.U)
        query(read = loadDest); dut.io.allowed.expect(false.B) // RAW
        dut.clock.step()
        query(write = loadDest); dut.io.allowed.expect(false.B) // WAW
        dut.clock.step()
        query(write = BigInt(1) << 10, memory = true)
        dut.io.allowed.expect(false.B) // A second independent memory is ordered.
        dut.clock.step()
        query(read = 6, write = 8); accept(1)
        dut.clock.step(3)
        dut.io.memoryComputeOverlap.peek().litValue should be > BigInt(0)
        release(1) // A younger completion cannot free the memory owner.
        query(memory = true); dut.io.allowed.expect(false.B)
        release(0)
        dut.io.allowed.expect(true.B)
        dut.io.busy.expect(false.B)

        // Store's source remains reserved through the final acknowledged
        // completion, even if all operand reads happened much earlier.
        query(read = 8, memory = true); accept(2)
        query(write = 8); dut.io.allowed.expect(false.B) // WAR
        dut.clock.step()
        query(read = 8, write = 16); accept(3) // Read/read sharing is allowed.
        release(3)
        release(2)

        // The symmetric direction also overlaps: new memory behind compute.
        query(read = 6, write = 8); accept(4)
        query(write = 256, memory = true); accept(5)
        query(write = 2); dut.io.allowed.expect(false.B) // Older compute read.
        dut.io.matrixReadMask.poke(1024.U)
        query(write = 1024); dut.io.allowed.expect(false.B)
        dut.io.matrixReadMask.poke(0.U)
        dut.io.allowed.expect(true.B)
        dut.io.memoryOrderStalls.peek().litValue should be > BigInt(0)
        dut.io.registerHazardStalls.peek().litValue should be > BigInt(0)
        dut.io.flush.poke(true.B)
        dut.io.allowed.expect(false.B)
        dut.clock.step()
        dut.io.flush.poke(false.B)
        dut.io.busy.expect(false.B)
        dut.io.memoryOrderStalls.expect(0.U)
        dut.io.registerHazardStalls.expect(0.U)
        dut.io.memoryComputeOverlap.expect(0.U)
        query(write = 8, memory = true); accept(0)
        release(0)
        dut.io.busy.expect(false.B)
      }
  }

  it should "delegate ordinary pairs to EG hazards while retaining special and matrix protection" in {
    test(new EvuCommandScoreboard(EvuParams(enable = true)))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.commandValid.poke(true.B)
        dut.io.readMask.poke(0.U)
        dut.io.writeMask.poke(8.U)
        dut.io.matrixReadMask.poke(0.U)
        dut.io.isMemory.poke(true.B)
        dut.io.chainable.poke(true.B)
        dut.io.jobId.poke(0.U)
        dut.io.accept.poke(true.B)
        dut.io.release.valid.poke(false.B)
        dut.io.release.bits.poke(0.U)
        dut.io.flush.poke(false.B)
        dut.clock.step()
        dut.io.accept.poke(false.B)
        dut.io.isMemory.poke(false.B)
        dut.io.readMask.poke(8.U)
        dut.io.writeMask.poke(16.U)
        dut.io.allowed.expect(true.B)
        dut.io.chainable.poke(false.B)
        dut.io.allowed.expect(false.B)
        dut.io.chainable.poke(true.B)
        dut.io.matrixReadMask.poke(16.U)
        dut.io.allowed.expect(false.B)
        dut.io.matrixReadMask.poke(0.U)
        dut.io.isMemory.poke(true.B)
        dut.io.allowed.expect(false.B)
        dut.io.isMemory.poke(false.B)
        dut.io.release.valid.poke(true.B)
        dut.io.release.bits.poke(0.U)
        dut.clock.step()
        dut.io.release.valid.poke(false.B)
        dut.io.chainable.poke(false.B)
        dut.io.jobId.poke(1.U)
        dut.io.accept.poke(true.B)
        dut.clock.step()
        dut.io.accept.poke(false.B)
        dut.io.chainable.poke(true.B)
        dut.io.readMask.poke(16.U)
        dut.io.writeMask.poke(32.U)
        dut.io.allowed.expect(false.B)
        dut.io.flush.poke(true.B)
        dut.clock.step()
        dut.io.flush.poke(false.B)
        dut.io.allowed.expect(true.B)
      }
  }
}
