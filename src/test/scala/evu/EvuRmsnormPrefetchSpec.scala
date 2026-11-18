package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.{AraRegisterFile, RegisterFile, RegisterReadCollector}
import xiangshan.evu.common.VectorParams

class EvuRmsnormPrefetchSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "RMSNorm E0-to-vector operand reuse"

  private def checkReuse(withPrefetch: Boolean, consumeScalar: Boolean, externalBypass: Boolean = false): Unit = {
    implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 4096, 512, 512, VectorParams.lgvParams.copy(vrfBanking = 4, debugVrf = true))
    test(new RegisterFile(Seq(1), Seq(1), 1, 1, 4, s"vrf_rmsnorm_prefetch_${withPrefetch}_${consumeScalar}_${externalBypass}",
      prefetchPorts = Seq((0, 0)), scalarPorts = Seq((0, 0)),
      bypassPorts = if (externalBypass) Seq((0, 0)) else Seq.empty))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        val read = dut.io.read(0)(0)
        val write = dut.io.pipe_writes(0)
        val prefetch = dut.io.prefetch(0)
        val eg = 31 * 8
        val mask = (BigInt(1) << 512) - 1
        val sixes = (0 until 16).foldLeft(BigInt(0))((bits, lane) =>
          bits | (BigInt("40c00000", 16) << (32 * lane)))
        for (port <- Seq(read, dut.io.mask_read(0)(0))) {
          port.req.valid.poke(false.B)
          port.req.bits.eg.poke(eg.U)
          port.req.bits.oldest.poke(false.B)
          port.req.bits.lastOperand.poke(false.B)
        }
        dut.io.e0Read(0).poke(false.B)
        dut.io.bypass.foreach(_.poke(false.B))
        prefetch.valid.poke(false.B)
        prefetch.bits.eg.poke(eg.U)
        prefetch.bits.oldest.poke(false.B)
        prefetch.bits.lastOperand.poke(false.B)
        val ll = dut.io.ll_writes(0)
        ll.valid.poke(false.B)
        ll.bits.eg.poke(eg.U)
        ll.bits.mask.poke("hffffffff".U)
        ll.bits.data.poke("h3f800000".U)
        val reservation = dut.io.pipe_write_reqs(0)
        reservation.request.poke(false.B)
        reservation.fire.poke(false.B)
        reservation.bank_sel.poke(0.U)
        reservation.pipe_depth.poke(0.U)
        reservation.oldest.poke(false.B)

        // SCALE_DIV_EPS: v31 contains FP32 512 in E0 and zero tail lanes.
        write.valid.poke(true.B)
        write.bits.eg.poke(eg.U)
        write.bits.mask.poke(mask.U)
        write.bits.data.poke("h44000000".U)
        dut.clock.step()
        write.valid.poke(false.B)
        prefetch.valid.poke(withPrefetch.B)
        dut.clock.step()
        prefetch.valid.poke(false.B)
        dut.io.perf.prefetches.expect((if (withPrefetch) 1 else 0).U)

        // The following scalar division reads the E0 mirror, not the collector.
        if (consumeScalar) {
          dut.io.e0Read(0).poke(true.B)
          read.req.valid.poke(true.B)
          read.req.ready.expect(true.B)
          read.resp.expect("h44000000".U)
          dut.clock.step()
        }
        if (externalBypass) {
          dut.io.bypass(0).poke(true.B)
          prefetch.valid.poke(true.B)
          dut.clock.step()
          dut.io.bypass(0).poke(false.B)
          prefetch.valid.poke(false.B)
          dut.io.perf.prefetches.expect(1.U)
        }
        read.req.valid.poke(false.B)
        dut.io.e0Read(0).poke(false.B)
        dut.clock.step(2)

        // Quantization LOAD_E0 and BROADCAST physically replace the same EG.
        write.valid.poke(true.B)
        write.bits.mask.poke("hffffffff".U)
        write.bits.data.poke("h40c00000".U)
        dut.clock.step()
        write.bits.mask.poke(mask.U)
        write.bits.data.poke(sixes.U)
        dut.clock.step()
        write.valid.poke(false.B)
        dut.clock.step(2)
        dut.io.debug.get(eg).expect(sixes.U)

        read.req.valid.poke(true.B)
        var waited = 0
        while (!read.req.ready.peek().litToBoolean && waited < 8) {
          dut.clock.step()
          waited += 1
        }
        read.req.ready.expect(true.B)
        read.resp.expect(sixes.U)
        dut.clock.step()
        read.req.valid.poke(false.B)

        // Staged LL writes invalidate on physical commit, including partial EG updates.
        prefetch.valid.poke(true.B)
        dut.clock.step()
        prefetch.valid.poke(false.B)
        dut.clock.step(2)
        ll.valid.poke(true.B)
        ll.ready.expect(true.B)
        dut.clock.step()
        ll.valid.poke(false.B)
        dut.clock.step(2)
        val patched = (sixes & (mask ^ BigInt("ffffffff", 16))) | BigInt("3f800000", 16)
        dut.io.debug.get(eg).expect(patched.U)
        read.req.valid.poke(true.B)
        read.req.ready.expect(false.B)
        dut.clock.step()
        read.req.ready.expect(true.B)
        read.resp.expect(patched.U)
      }
  }

  for ((withPrefetch, consumeScalar) <- Seq((false, true), (true, true), (true, false))) {
    it should s"read the rewritten broadcast (prefetch=$withPrefetch, E0 bypass=$consumeScalar)" in {
      checkReuse(withPrefetch, consumeScalar)
    }
  }

  it should "cancel a real VRF prefetch on external result bypass and suppress its matching hint" in {
    checkReuse(withPrefetch = true, consumeScalar = false, externalBypass = true)
  }

  for (bypass <- Seq(false, true); responseState <- Seq("buffered", "returning", "pending")) {
    it should s"drain invalidated responses before retrying (bypass=$bypass, response=$responseState)" in {
      implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
        case freechips.rocketchip.tile.XLen => 64
        case freechips.rocketchip.tile.MaxHartIdBits => 1
        case freechips.rocketchip.rocket.PgLevels => 3
      }, 4096, 512, 512, VectorParams.lgvParams.copy(vrfBanking = 4))
      test(new RegisterReadCollector(2)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.client.req.valid.poke(false.B)
        dut.io.client.req.bits.eg.poke(248.U)
        dut.io.client.req.bits.oldest.poke(false.B)
        dut.io.client.req.bits.lastOperand.poke(false.B)
        dut.io.bypass.poke(false.B)
        dut.io.invalidate.foreach { w => w.valid.poke(false.B); w.bits.poke(248.U) }
        dut.io.prefetch.valid.poke(true.B)
        dut.io.prefetch.bits.eg.poke(248.U)
        dut.io.prefetch.bits.oldest.poke(false.B)
        dut.io.prefetch.bits.lastOperand.poke(false.B)
        dut.io.memory.req.ready.poke(true.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.poke(512.U)
        dut.clock.step()
        dut.io.prefetch.valid.poke(false.B)
        if (responseState == "buffered") {
          dut.io.memory.resp.valid.poke(true.B)
          dut.clock.step()
          dut.io.memory.resp.valid.poke(false.B)
        } else if (responseState == "returning") dut.io.memory.resp.valid.poke(true.B)

        if (bypass) dut.io.bypass.poke(true.B)
        else dut.io.invalidate(1).valid.poke(true.B)
        if (responseState == "pending") {
          dut.clock.step()
          dut.io.bypass.poke(false.B)
          dut.io.invalidate(1).valid.poke(false.B)
          dut.io.client.req.valid.poke(true.B)
          dut.io.client.req.ready.expect(false.B)
          dut.io.memory.req.valid.expect(false.B)
          dut.io.memory.resp.valid.poke(true.B)
        } else dut.io.client.req.valid.poke(true.B)
        dut.io.client.req.ready.expect(false.B)
        dut.io.prefetchDiscarded.expect(true.B)
        dut.io.memory.req.valid.expect(true.B)
        dut.io.memory.speculative.expect(false.B)
        dut.clock.step()
        dut.io.bypass.poke(false.B)
        dut.io.invalidate(1).valid.poke(false.B)
        dut.io.memory.resp.valid.poke(true.B)
        dut.io.memory.resp.bits.poke(6.U)
        dut.io.client.req.ready.expect(true.B)
        dut.io.client.resp.expect(6.U)
        dut.io.prefetchUsed.expect(false.B)
      }
    }
  }

  it should "retain a hint across unrelated writes and cancel it on an E0 bypass without a rewrite" in {
    implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 4096, 512, 512, VectorParams.lgvParams.copy(vrfBanking = 4))
    test(new RegisterReadCollector(1)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.client.req.valid.poke(false.B)
      dut.io.client.req.bits.eg.poke(248.U)
      dut.io.client.req.bits.oldest.poke(false.B)
      dut.io.client.req.bits.lastOperand.poke(false.B)
      dut.io.bypass.poke(false.B)
      dut.io.invalidate(0).valid.poke(false.B)
      dut.io.invalidate(0).bits.poke(249.U)
      dut.io.prefetch.valid.poke(true.B)
      dut.io.prefetch.bits.eg.poke(248.U)
      dut.io.prefetch.bits.oldest.poke(false.B)
      dut.io.prefetch.bits.lastOperand.poke(false.B)
      dut.io.memory.req.ready.poke(true.B)
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.memory.resp.bits.poke(6.U)
      dut.clock.step()
      dut.io.prefetch.valid.poke(false.B)
      dut.io.memory.resp.valid.poke(true.B)
      dut.clock.step()
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.invalidate(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.invalidate(0).valid.poke(false.B)
      dut.io.client.req.valid.poke(true.B)
      dut.io.client.req.ready.expect(true.B)
      dut.io.prefetchUsed.expect(true.B)
      dut.io.client.resp.expect(6.U)
      // RegisterFile hides a scalar-hit demand from this client interface.
      dut.io.client.req.valid.poke(false.B)
      dut.io.bypass.poke(true.B)
      dut.io.prefetch.valid.poke(true.B)
      dut.io.prefetchDiscarded.expect(true.B)
      dut.io.memory.req.valid.expect(false.B)
      dut.clock.step()
      dut.io.bypass.poke(false.B)
      dut.io.prefetch.valid.poke(false.B)
      dut.io.client.req.valid.poke(true.B)
      dut.io.client.req.ready.expect(false.B)
      dut.io.memory.req.valid.expect(true.B)
    }
  }

  it should "notify invalidation only for nonempty physical writes without diagnostics or owners" in {
    implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 512, 256, 128, VectorParams.lgvParams.copy(vrfBanking = 4, diagnosticCounters = false))
    test(new AraRegisterFile(Seq(1), Seq(1), 1, 1, 4, "vrf_invalidation_notification"))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        for (port <- Seq(dut.io.read(0)(0), dut.io.mask_read(0)(0))) {
          port.req.valid.poke(false.B)
          port.req.bits.eg.poke(0.U)
          port.req.bits.oldest.poke(false.B)
          port.req.bits.lastOperand.poke(false.B)
          port.speculative.poke(false.B)
        }
        val reservation = dut.io.pipe_write_reqs(0)
        reservation.request.poke(false.B)
        reservation.fire.poke(false.B)
        reservation.bank_sel.poke(0.U)
        reservation.pipe_depth.poke(0.U)
        reservation.oldest.poke(false.B)
        val pipe = dut.io.pipe_writes(0)
        val ll = dut.io.ll_writes(0)
        pipe.valid.poke(false.B)
        pipe.bits.eg.poke(6.U) // v3 E0 maps to bank 1 at two EGs per register.
        pipe.bits.mask.poke(0.U)
        pipe.bits.data.poke(0.U)
        ll.valid.poke(false.B)
        ll.bits.eg.poke(6.U)
        ll.bits.mask.poke("hffffffff".U)
        ll.bits.data.poke(1.U)
        def expectWrite(valid: Boolean): Unit = {
          for (bank <- 0 until 4) {
            dut.io.writeInvalidations(bank).valid.expect((valid && bank == 1).B)
            if (valid && bank == 1) dut.io.writeInvalidations(bank).bits.expect(6.U)
          }
        }
        pipe.valid.poke(true.B)
        expectWrite(false)
        dut.clock.step()
        pipe.bits.mask.poke("hff00000000".U)
        expectWrite(true)
        dut.clock.step()
        pipe.valid.poke(false.B)
        ll.valid.poke(true.B)
        ll.ready.expect(true.B)
        expectWrite(false)
        dut.clock.step()
        ll.valid.poke(false.B)
        expectWrite(true)
        dut.clock.step()
        expectWrite(false)
        ll.valid.poke(true.B)
        ll.bits.mask.poke(0.U)
        dut.clock.step()
        ll.valid.poke(false.B)
        expectWrite(false)
        dut.clock.step()
        expectWrite(false)
      }
  }
}
