package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.backend.WriteCompletionTracker
import xiangshan.evu.backend.AraRegisterFile
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common.VectorParams
import org.chipsalliance.cde.config.Parameters

class EvuWriteCompletionSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Physical VRF write completion"

  it should "keep LL ownership and byte masks live while a scheduled write occupies the same 1RW bank" in {
    implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 512, 256, 256, VectorParams.lgvParams.copy(debugVrf = true))
    test(new AraRegisterFile(Seq(1), Seq(1), 1, 1, 4, "vrf_write_commit", trackWrites = true))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.read(0)(0).req.valid.poke(false.B)
        dut.io.mask_read(0)(0).req.valid.poke(false.B)
        dut.io.pipe_write_reqs(0).request.poke(false.B)
        dut.io.pipe_write_reqs(0).fire.poke(false.B)
        dut.io.pipe_write_reqs(0).bank_sel.poke(0.U)
        dut.io.pipe_write_reqs(0).pipe_depth.poke(0.U)
        dut.io.pipe_write_reqs(0).oldest.poke(false.B)
        dut.io.pipe_writes(0).valid.poke(false.B)
        dut.io.ll_writes(0).valid.poke(true.B)
        dut.io.ll_writes(0).bits.eg.poke(0.U)
        dut.io.ll_writes(0).bits.mask.poke(0xffffffffL.U)
        dut.io.ll_writes(0).bits.data.poke(0x12345678L.U)
        dut.io.llOwners.get(0).valid.poke(true.B)
        dut.io.llOwners.get(0).bits.poke(7.U)
        dut.io.ll_writes(0).ready.expect(true.B)
        dut.clock.step()
        dut.io.ll_writes(0).valid.poke(false.B)
        dut.io.pipe_writes(0).valid.poke(true.B)
        dut.io.pipe_writes(0).bits.eg.poke(0.U)
        dut.io.pipe_writes(0).bits.mask.poke(((BigInt(1) << 256) - 1).U)
        dut.io.pipe_writes(0).bits.data.poke(0.U)
        for (_ <- 0 until 3) {
          dut.io.pendingWrites.get(0).valid.expect(true.B)
          dut.io.pendingWrites.get(0).bits.vat.expect(7.U)
          dut.io.pendingWrites.get(0).bits.mask.expect(0xffffffffL.U)
          dut.io.writeCommits.get(0).valid.expect(false.B)
          dut.clock.step()
        }
        dut.io.pipe_writes(0).valid.poke(false.B)
        dut.io.writeCommits.get(0).valid.expect(true.B)
        dut.clock.step()
        dut.io.pendingWrites.get(0).valid.expect(false.B)
        dut.io.debug.get(0).expect(0x12345678L.U)
      }
  }

  it should "hold completed tails until every buffered bank write drains and retain simultaneous tails" in {
    test(new WriteCompletionTracker(3, 3)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.tails.foreach { t => t.valid.poke(false.B); t.bits.poke(0.U) }
      dut.io.pendingWrites.poke(2.U)
      dut.io.tails(0).valid.poke(true.B)
      dut.io.tails(0).bits.poke(1.U)
      dut.io.tails(1).valid.poke(true.B)
      dut.io.tails(1).bits.poke(3.U)
      dut.clock.step()
      dut.io.tails.foreach(_.valid.poke(false.B))
      dut.io.release.valid.expect(true.B)
      dut.io.release.bits.expect(3.U)
      dut.clock.step()
      dut.io.release.valid.expect(false.B)
      dut.clock.step(4)
      dut.io.pendingWrites.poke(0.U)
      dut.io.release.valid.expect(true.B)
      dut.io.release.bits.expect(1.U)
      dut.clock.step()
      dut.io.busy.expect(false.B)
      dut.io.tails(2).valid.poke(true.B)
      dut.io.tails(2).bits.poke(1.U)
      dut.clock.step()
      dut.io.tails(2).valid.poke(false.B)
      dut.io.release.bits.expect(1.U)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.release.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }
}
