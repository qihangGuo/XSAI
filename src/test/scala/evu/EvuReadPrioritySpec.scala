package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.{AraRegisterFile, OldestRRArbiter, RegisterReadCollector}
import xiangshan.evu.common.VectorParams

class EvuReadPrioritySpec extends AnyFlatSpec with ChiselScalatestTester {
  private def parameters: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }, 512, 256, 128, VectorParams.lgvParams.copy(diagnosticCounters = true))

  behavior of "EVU demand-first VRF reads"

  it should "serve a demand before an oldest prefetch and retain round-robin demand fairness" in {
    implicit val p: Parameters = parameters
    test(new OldestRRArbiter(3)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      for (i <- 0 until 3) {
        dut.io.in(i).valid.poke(true.B)
        dut.io.in(i).bits.eg.poke(i.U)
        dut.io.in(i).bits.oldest.poke((i == 0).B)
        dut.io.speculative(i).poke((i == 0).B)
      }
      dut.io.out.ready.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.in.foreach(_.ready.expect(false.B))
      dut.clock.step(3)
      dut.io.out.ready.poke(true.B)
      var previous = -1
      for (_ <- 0 until 8) {
        val winner = dut.io.chosen.peek().litValue.toInt
        assert(winner == 1 || winner == 2)
        assert(winner != previous)
        previous = winner
        dut.io.in(0).ready.expect(false.B)
        dut.clock.step()
      }
      dut.io.in(1).valid.poke(false.B)
      dut.io.in(2).valid.poke(false.B)
      dut.io.chosen.expect(0.U)
      dut.io.in(0).ready.expect(true.B)
    }
  }

  it should "collect the current demand before a hint and reuse the successor response" in {
    implicit val p: Parameters = parameters
    test(new RegisterReadCollector).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.client.req.valid.poke(true.B)
      dut.io.client.req.bits.eg.poke(3.U)
      dut.io.client.req.bits.oldest.poke(false.B)
      dut.io.prefetch.valid.poke(true.B)
      dut.io.prefetch.bits.eg.poke(4.U)
      dut.io.prefetch.bits.oldest.poke(true.B)
      dut.io.memory.req.ready.poke(true.B)
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.memory.resp.bits.poke(0.U)
      dut.io.memory.req.bits.eg.expect(3.U)
      dut.io.memory.speculative.expect(false.B)
      dut.io.prefetched.expect(false.B)
      dut.clock.step()
      dut.io.memory.resp.valid.poke(true.B)
      dut.io.memory.resp.bits.poke(0x123.U)
      dut.io.client.req.ready.expect(true.B)
      dut.io.client.resp.expect(0x123.U)
      dut.io.memory.req.bits.eg.expect(4.U)
      dut.io.memory.speculative.expect(true.B)
      dut.io.prefetched.expect(true.B)
      dut.clock.step()
      dut.io.prefetch.valid.poke(false.B)
      dut.io.client.req.bits.eg.poke(4.U)
      dut.io.memory.resp.bits.poke(0x456.U)
      dut.io.client.req.ready.expect(true.B)
      dut.io.client.resp.expect(0x456.U)
      dut.io.prefetchUsed.expect(true.B)
      dut.io.memory.req.valid.expect(false.B)
      dut.clock.step()
      dut.io.client.req.valid.poke(false.B)
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.prefetchUsed.expect(false.B)
    }
  }

  it should "count an outstanding wrong hint and replace it when its response arrives" in {
    implicit val p: Parameters = parameters
    test(new RegisterReadCollector).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.client.req.valid.poke(false.B)
      dut.io.client.req.bits.eg.poke(3.U)
      dut.io.client.req.bits.oldest.poke(false.B)
      dut.io.prefetch.valid.poke(true.B)
      dut.io.prefetch.bits.eg.poke(4.U)
      dut.io.prefetch.bits.oldest.poke(false.B)
      dut.io.memory.req.ready.poke(true.B)
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.memory.resp.bits.poke(0.U)
      dut.clock.step()
      dut.io.prefetch.valid.poke(false.B)
      dut.io.client.req.valid.poke(true.B)
      dut.io.demandPrefetchWait.expect(true.B)
      dut.io.memory.req.valid.expect(false.B)
      dut.clock.step()
      dut.io.memory.resp.valid.poke(true.B)
      dut.io.memory.resp.bits.poke(0xbad.U)
      dut.io.prefetchDiscarded.expect(true.B)
      dut.io.client.req.ready.expect(false.B)
      dut.io.memory.req.valid.expect(true.B)
      dut.io.memory.req.bits.eg.expect(3.U)
      dut.io.memory.speculative.expect(false.B)
      dut.clock.step()
      dut.io.memory.resp.bits.poke(0x789.U)
      dut.io.client.req.ready.expect(true.B)
      dut.io.client.resp.expect(0x789.U)
      dut.io.prefetchUsed.expect(false.B)
      dut.reset.poke(true.B)
      dut.io.client.req.ready.expect(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.client.req.ready.expect(false.B)
    }
  }

  it should "preserve demand priority through both levels and equal-row sharing" in {
    implicit val p: Parameters = parameters
    test(new AraRegisterFile(Seq(2, 1), Seq(1), 1, 1, 4, "vrf_demand_priority"))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        val ports = Seq(dut.io.read(0)(0), dut.io.read(0)(1), dut.io.read(1)(0), dut.io.mask_read(0)(0))
        ports.foreach { port =>
          port.req.valid.poke(false.B)
          port.req.bits.eg.poke(0.U)
          port.req.bits.oldest.poke(false.B)
          port.speculative.poke(false.B)
        }
        dut.io.pipe_write_reqs(0).request.poke(false.B)
        dut.io.pipe_write_reqs(0).fire.poke(false.B)
        dut.io.pipe_write_reqs(0).bank_sel.poke(0.U)
        dut.io.pipe_write_reqs(0).pipe_depth.poke(0.U)
        dut.io.pipe_write_reqs(0).oldest.poke(false.B)
        dut.io.ll_writes(0).valid.poke(false.B)
        val write = dut.io.pipe_writes(0)
        write.bits.mask.poke(((BigInt(1) << 256) - 1).U)
        for ((eg, value) <- Seq(3 -> 0x123, 19 -> 0x456)) {
          write.valid.poke(true.B)
          write.bits.eg.poke(eg.U)
          write.bits.data.poke(value.U)
          dut.clock.step()
        }
        write.valid.poke(false.B)
        ports(0).req.valid.poke(true.B)
        ports(0).req.bits.eg.poke(3.U)
        ports(0).req.bits.oldest.poke(true.B)
        ports(0).speculative.poke(true.B)
        ports(1).req.valid.poke(true.B)
        ports(1).req.bits.eg.poke(19.U)
        ports(0).req.ready.expect(false.B)
        ports(1).req.ready.expect(true.B)
        dut.clock.step()
        ports(1).req.valid.poke(false.B)
        ports(1).resp.valid.expect(true.B)
        ports(1).resp.bits.expect(0x456.U)

        // The equal-row demand must carry its priority past an older hint.
        ports(2).req.valid.poke(true.B)
        ports(2).req.bits.eg.poke(3.U)
        ports(3).req.valid.poke(true.B)
        ports(3).req.bits.eg.poke(19.U)
        ports(3).req.bits.oldest.poke(true.B)
        ports(3).speculative.poke(true.B)
        ports(0).req.ready.expect(true.B)
        ports(2).req.ready.expect(true.B)
        ports(3).req.ready.expect(false.B)
        dut.io.prefetchDeferred.expect(true.B)
        dut.io.demandPrefetchBlocked.expect(false.B)
        dut.clock.step()
        ports.foreach(_.req.valid.poke(false.B))
        for (i <- Seq(0, 2)) {
          ports(i).resp.valid.expect(true.B)
          ports(i).resp.bits.expect(0x123.U)
        }
        ports(3).resp.valid.expect(false.B)
      }
  }
}
