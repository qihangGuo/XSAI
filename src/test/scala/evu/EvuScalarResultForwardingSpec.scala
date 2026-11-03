package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.{RegisterFile, ScalarResultForwarding}
import xiangshan.evu.common.VectorParams

class EvuScalarResultForwardingSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Committed FP32 element-zero forwarding"

  it should "merge enabled bytes, track concurrent registers and invalidate on reset" in {
    test(new ScalarResultForwarding(32, 2)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.writes.foreach { w =>
        w.valid.poke(false.B)
        w.bits.register.poke(0.U)
        w.bits.data.poke(0.U)
        w.bits.mask.poke(0.U)
      }
      dut.io.values.foreach(_.valid.expect(false.B))
      val a = dut.io.writes(0)
      val b = dut.io.writes(1)
      a.valid.poke(true.B)
      a.bits.register.poke(31.U)
      a.bits.data.poke("h11223344".U)
      a.bits.mask.poke(3.U)
      dut.clock.step()
      dut.io.values(31).valid.expect(false.B)
      a.bits.mask.poke(12.U)
      b.valid.poke(true.B)
      b.bits.register.poke(7.U)
      b.bits.data.poke("h7f800001".U)
      b.bits.mask.poke(15.U)
      dut.clock.step()
      dut.io.values(31).valid.expect(true.B)
      dut.io.values(31).bits.expect("h11223344".U)
      dut.io.values(7).bits.expect("h7f800001".U)
      a.bits.mask.poke(2.U)
      a.bits.data.poke("h0000aa00".U)
      b.valid.poke(false.B)
      dut.clock.step()
      dut.io.values(31).bits.expect("h1122aa44".U)
      a.bits.mask.poke(0.U)
      a.bits.data.poke(0.U)
      dut.clock.step()
      dut.io.values(31).bits.expect("h1122aa44".U)
      a.valid.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.values.foreach(_.valid.expect(false.B))
    }
  }

  it should "serve E0 after physical commit, preserve vector tails and refresh from LL and pipe writes" in {
    implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 512, 256, 128, VectorParams.lgvParams.copy(debugVrf = true))
    test(new RegisterFile(Seq(2), Seq(1), 1, 1, 4, "vrf_e0_forward",
      scalarPorts = Seq((0, 0), (0, 1))))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        val reads = dut.io.read(0)
        (reads.toSeq ++ dut.io.mask_read(0).toSeq).foreach { r =>
          r.req.valid.poke(false.B)
          r.req.bits.eg.poke(0.U)
          r.req.bits.oldest.poke(false.B)
        }
        dut.io.e0Read.foreach(_.poke(false.B))
        val reservation = dut.io.pipe_write_reqs(0)
        reservation.request.poke(false.B)
        reservation.fire.poke(false.B)
        reservation.bank_sel.poke(0.U)
        reservation.pipe_depth.poke(0.U)
        reservation.oldest.poke(false.B)
        val pipe = dut.io.pipe_writes(0)
        val ll = dut.io.ll_writes(0)
        pipe.valid.poke(false.B)
        ll.valid.poke(false.B)
        val fullMask = (BigInt(1) << 256) - 1
        val original = (BigInt("12345678" * 7, 16) << 32) | BigInt("3f800000", 16)
        pipe.valid.poke(true.B)
        pipe.bits.eg.poke(6.U) // E0 of v3 at two EGs/register.
        pipe.bits.data.poke(original.U)
        pipe.bits.mask.poke(fullMask.U)
        dut.clock.step()
        pipe.valid.poke(false.B)
        reads(0).req.bits.eg.poke(6.U)
        reads(0).req.valid.poke(true.B)
        dut.io.e0Read(0).poke(true.B)
        reads(0).req.ready.expect(true.B)
        reads(0).resp.expect("h3f800000".U)
        dut.clock.step()
        reads(0).req.valid.poke(false.B)

        // Non-scalar reads must use SRAM and return every preserved tail byte.
        reads(1).req.bits.eg.poke(6.U)
        reads(1).req.valid.poke(true.B)
        reads(1).req.ready.expect(false.B)
        dut.clock.step()
        reads(1).req.ready.expect(true.B)
        reads(1).resp.expect(original.U)
        dut.clock.step()
        reads(1).req.valid.poke(false.B)

        // LL acceptance stages data; the mirror must not change before the
        // physical write. A pipe write on the bank delays that commit.
        ll.valid.poke(true.B)
        ll.bits.eg.poke(6.U)
        ll.bits.data.poke("h40000000".U)
        ll.bits.mask.poke("hffffffff".U)
        ll.ready.expect(true.B)
        dut.clock.step()
        ll.valid.poke(false.B)
        pipe.valid.poke(true.B)
        pipe.bits.data.poke(0.U)
        pipe.bits.mask.poke(fullMask.U)
        // Use a same-bank different register, avoiding the E0 being tested.
        pipe.bits.eg.poke(22.U)
        reads(0).req.valid.poke(true.B)
        reads(0).resp.expect("h3f800000".U)
        dut.clock.step(2)
        reads(0).resp.expect("h3f800000".U)
        pipe.valid.poke(false.B)
        dut.clock.step()
        reads(0).resp.expect("h40000000".U)
        val expected = (original & (fullMask ^ BigInt("ffffffff", 16))) | BigInt("40000000", 16)
        dut.io.debug.get(6).expect(expected.U)
        dut.io.perf.scalarForwards.expect(4.U)
        reads(0).req.valid.poke(false.B)

        // A zero mask and a write to a nonzero EG cannot replace E0.
        pipe.valid.poke(true.B)
        pipe.bits.eg.poke(6.U)
        pipe.bits.mask.poke(0.U)
        pipe.bits.data.poke(0.U)
        dut.clock.step()
        pipe.bits.eg.poke(7.U)
        pipe.bits.mask.poke(fullMask.U)
        dut.clock.step()
        pipe.valid.poke(false.B)
        reads(0).req.valid.poke(true.B)
        reads(0).resp.expect("h40000000".U)
        reads(0).req.valid.poke(false.B)
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        reads(0).req.valid.poke(true.B)
        reads(0).req.ready.expect(false.B)
      }
  }
}
