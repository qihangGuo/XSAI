package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.{AraRegisterFile, RegisterReadCollector}
import xiangshan.evu.common.VectorParams

class EvuSkewedVrfSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Skewed synchronous 1RW EVU VRF"

  it should "retain a bijective bank-row mapping for every supported chunk count" in {
    for (chunks <- Seq(1, 2, 4, 8, 16, 32)) {
      val locations = (0 until 32 * chunks).map { eg =>
        val bank = if (chunks == 1) eg & 7 else (eg & 7) ^ ((eg / chunks) & 7)
        (eg >> 3, bank)
      }
      locations.distinct.size shouldBe 32 * chunks
    }
  }

  private def parameters(vlen: Int): Parameters = EvuVectorParameters(
    Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, vlen, 256, 128, VectorParams.lgvParams.copy(debugVrf = true))

  it should "map every EG bijectively, read three registers together and retain single-port exclusion" in {
    implicit val p: Parameters = parameters(4096)
    test(new AraRegisterFile(Seq(3), Seq(1), 1, 1, 4, "vrf_skew"))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        val reads = dut.io.read(0)
        reads.foreach(_.req.valid.poke(false.B))
        dut.io.mask_read(0)(0).req.valid.poke(false.B)
        dut.io.ll_writes(0).valid.poke(false.B)
        val reservation = dut.io.pipe_write_reqs(0)
        reservation.request.poke(false.B)
        reservation.fire.poke(false.B)
        reservation.bank_sel.poke(0.U)
        reservation.pipe_depth.poke(0.U)
        reservation.oldest.poke(false.B)
        val mask = (BigInt(1) << 256) - 1
        val write = dut.io.pipe_writes(0)
        val values = (0 until 512).map(i => (BigInt(i + 1) << 192) | BigInt(0x10000 + i))
        write.valid.poke(true.B)
        for (eg <- values.indices) {
          write.bits.eg.poke(eg.U)
          write.bits.data.poke(values(eg).U)
          write.bits.mask.poke(mask.U)
          dut.clock.step()
        }
        write.valid.poke(false.B)
        for (eg <- values.indices) {
          reads(0).req.valid.poke(true.B)
          reads(0).req.bits.eg.poke(eg.U)
          reads(0).req.bits.oldest.poke(false.B)
          reads(0).req.ready.expect(true.B)
          dut.clock.step()
          reads(0).resp.valid.expect(true.B)
          reads(0).resp.bits.expect(values(eg).U)
          dut.io.debug.get(eg).expect(values(eg).U)
        }
        reads(0).req.valid.poke(false.B)
        dut.clock.step()
        // v1/v2/v3 chunk 0 all used bank 0 before skewing. Now banks 1/2/3.
        for (i <- 0 until 3) {
          reads(i).req.valid.poke(true.B)
          reads(i).req.bits.eg.poke(((i + 1) * 16).U)
          reads(i).req.bits.oldest.poke(true.B)
          reads(i).req.ready.expect(true.B)
        }
        dut.clock.step()
        reads.foreach(_.req.valid.poke(false.B))
        for (i <- 0 until 3) { reads(i).resp.bits.expect(values((i + 1) * 16).U) }
        dut.clock.step()
        // v1 chunk 0 and v9 chunk 0 still conflict: skew is not extra ports.
        reads(0).req.bits.eg.poke(16.U)
        reads(1).req.bits.eg.poke(144.U)
        reads(0).req.valid.poke(true.B)
        reads(1).req.valid.poke(true.B)
        Seq(reads(0), reads(1)).count(_.req.ready.peek().litToBoolean) shouldBe 1
        dut.clock.step()
        dut.io.perf.bankConflicts.peek().litValue should be > BigInt(0)
        write.valid.poke(true.B)
        write.bits.eg.poke(16.U)
        write.bits.data.poke(777.U)
        write.bits.mask.poke(mask.U)
        reads(0).req.ready.expect(false.B)
        reads(1).req.ready.expect(false.B)
        dut.clock.step()
        dut.io.perf.writeBlocksRead.peek().litValue should be > BigInt(0)
        write.valid.poke(false.B)
        reads.foreach(_.req.valid.poke(false.B))
      }
  }

  it should "pipeline successor reads on response consumption and buffer them across execution stalls" in {
    implicit val p: Parameters = parameters(4096)
    test(new RegisterReadCollector).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.client.req.bits.oldest.poke(false.B)
      dut.io.client.req.bits.eg.poke(16.U)
      dut.io.client.req.valid.poke(true.B)
      dut.io.prefetch.valid.poke(false.B)
      dut.io.prefetch.bits.oldest.poke(false.B)
      dut.io.prefetch.bits.eg.poke(17.U)
      dut.io.memory.req.ready.poke(true.B)
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.memory.resp.bits.poke(100.U)
      dut.io.memory.req.valid.expect(true.B)
      dut.clock.step()
      for (eg <- 16 until 23) {
        dut.io.client.req.bits.eg.poke(eg.U)
        dut.io.memory.resp.valid.poke(true.B)
        dut.io.memory.resp.bits.poke((100 + eg - 16).U)
        dut.io.prefetch.valid.poke(true.B)
        dut.io.prefetch.bits.eg.poke((eg + 1).U)
        dut.io.client.req.ready.expect(true.B)
        dut.io.client.resp.expect((100 + eg - 16).U)
        dut.io.memory.req.valid.expect(true.B)
        dut.io.memory.req.bits.eg.expect((eg + 1).U)
        dut.io.prefetched.expect(true.B)
        dut.clock.step()
      }
      dut.io.prefetch.valid.poke(false.B)
      dut.io.client.req.valid.poke(false.B)
      dut.io.memory.resp.bits.poke(107.U)
      dut.clock.step()
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.memory.resp.bits.poke(0.U)
      dut.clock.step(3)
      dut.io.client.req.valid.poke(true.B)
      dut.io.client.req.bits.eg.poke(23.U)
      dut.io.client.req.ready.expect(true.B)
      dut.io.client.resp.expect(107.U)
      dut.io.memory.req.valid.expect(false.B)
      dut.clock.step()
      dut.io.client.req.valid.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.client.req.valid.poke(true.B)
      dut.io.client.req.ready.expect(false.B)
      dut.io.memory.req.valid.expect(true.B)
    }
  }
}
