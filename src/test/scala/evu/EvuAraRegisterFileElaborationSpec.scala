package evu

import circt.stage.ChiselStage
import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.RegisterFile
import xiangshan.evu.common.VectorParams

/** Checks the physical Ara-style VRF graph without running a large SRAM model. */
class EvuAraRegisterFileElaborationSpec extends AnyFlatSpec with Matchers with ChiselScalatestTester {
  behavior of "Ara-style EVU VRF"

  it should "elaborate four 64-bit lanes and eight banks per lane" in {
    val base = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    implicit val p: Parameters = EvuVectorParameters(
      base,
      vectorLength = 4096,
      datapathWidth = 256,
      memoryWidth = 128,
      params = VectorParams.lgvParams.copy(debugVrf = true)
    )

    val chirrtl = ChiselStage.emitCHIRRTL(new RegisterFile(Seq(1, 1, 1), Seq(1), 1, 1, 4, "vrf_layout"))
    assert(chirrtl.contains("AraRegisterFile"))
    assert(chirrtl.contains("storage_0_0"))
    assert(chirrtl.contains("storage_3_7"))
    assert(chirrtl.contains("sram_array_1p64x64m8"))
    assert(!chirrtl.contains("sram_array_1p64x64m1s"))
    assert(chirrtl.contains("rdwr mport"))
  }

  it should "read back a 256-bit value assembled from four lane slices" in {
    val base = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    implicit val p: Parameters = EvuVectorParameters(
      base,
      vectorLength = 256,
      datapathWidth = 256,
      memoryWidth = 128,
      params = VectorParams.lgvParams
    )

    test(new RegisterFile(Seq(1), Seq(1), 1, 1, 4, "vrf_readback"))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.read(0)(0).req.valid.poke(false.B)
        dut.io.mask_read(0)(0).req.valid.poke(false.B)
        dut.io.pipe_write_reqs(0).request.poke(false.B)
        dut.io.pipe_write_reqs(0).fire.poke(false.B)
        dut.io.pipe_write_reqs(0).bank_sel.poke(0.U)
        dut.io.pipe_write_reqs(0).pipe_depth.poke(0.U)
        dut.io.pipe_write_reqs(0).oldest.poke(true.B)
        dut.io.ll_writes(0).valid.poke(false.B)

        val value = BigInt("0123456789abcdeffedcba9876543210", 16)
        val write = dut.io.pipe_writes(0)
        write.valid.poke(true.B)
        write.bits.eg.poke(5.U)
        write.bits.data.poke(value.U)
        write.bits.mask.poke(((BigInt(1) << 256) - 1).U)
        dut.clock.step()
        write.valid.poke(false.B)

        dut.io.read(0)(0).req.bits.eg.poke(5.U)
        dut.io.read(0)(0).req.bits.oldest.poke(true.B)
        dut.io.read(0)(0).req.valid.poke(true.B)
        dut.io.read(0)(0).req.ready.expect(false.B)
        dut.clock.step()
        dut.io.read(0)(0).req.ready.expect(true.B)
        dut.io.read(0)(0).resp.expect(value.U)
        dut.io.read(0)(0).req.valid.poke(false.B)
      }
  }
  it should "serialize three oldest requests to the same bank without merging their addresses" in {
    val base = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    implicit val p: Parameters = EvuVectorParameters(base, 512, 256, 128,
      VectorParams.lgvParams)
    test(new RegisterFile(Seq(3), Seq(1), 1, 1, 4, "vrf_conflict"))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.read(0).foreach(_.req.valid.poke(false.B))
        dut.io.mask_read(0)(0).req.valid.poke(false.B)
        dut.io.ll_writes(0).valid.poke(false.B)
        dut.io.pipe_write_reqs(0).request.poke(false.B)
        dut.io.pipe_write_reqs(0).fire.poke(false.B)
        dut.io.pipe_write_reqs(0).bank_sel.poke(0.U)
        dut.io.pipe_write_reqs(0).pipe_depth.poke(0.U)
        dut.io.pipe_write_reqs(0).oldest.poke(true.B)
        val values = Seq.tabulate(3)(i => (0 until 4).map(lane =>
          BigInt(0x12340000L + i*16 + lane) << (lane*64)).reduce(_ | _))
        val write = dut.io.pipe_writes(0)
        for (i <- values.indices) {
          write.valid.poke(true.B)
          write.bits.eg.poke((i*16).U)
          write.bits.data.poke(values(i).U)
          write.bits.mask.poke(((BigInt(1) << 256)-1).U)
          dut.clock.step()
        }
        write.valid.poke(false.B)
        for (i <- values.indices) {
          val read = dut.io.read(0)(i)
          read.req.valid.poke(true.B)
          read.req.bits.eg.poke((i*16).U)
          read.req.bits.oldest.poke(true.B)
        }
        val pending = scala.collection.mutable.Set(0, 1, 2)
        for (_ <- 0 until 8 if pending.nonEmpty) {
          val winners = pending.filter(i => dut.io.read(0)(i).req.ready.peek().litToBoolean).toSeq
          winners.size should be <= 1
          winners.foreach(selected => dut.io.read(0)(selected).resp.expect(values(selected).U))
          dut.clock.step()
          winners.foreach { selected =>
            dut.io.read(0)(selected).req.valid.poke(false.B)
            pending -= selected
          }
        }
        pending should be(empty)
        // A strict 1RW bank gives the write priority over a colliding read.
        dut.io.read(0)(0).req.valid.poke(true.B)
        write.valid.poke(true.B)
        write.bits.eg.poke(0.U)
        dut.io.read(0)(0).req.ready.expect(false.B)
      }
  }

  it should "coalesce identical same-bank reads and return one value to both requesters" in {
    val base = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    implicit val p: Parameters = EvuVectorParameters(base, 512, 256, 128,
      VectorParams.lgvParams.copy(debugVrf = true))
    test(new RegisterFile(Seq(1, 1), Seq(1), 1, 1, 4, "vrf_coalesce"))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.read.foreach(_.foreach(_.req.valid.poke(false.B)))
        dut.io.mask_read(0)(0).req.valid.poke(false.B)
        dut.io.ll_writes(0).valid.poke(false.B)
        dut.io.pipe_write_reqs(0).request.poke(false.B)
        dut.io.pipe_write_reqs(0).fire.poke(false.B)
        dut.io.pipe_write_reqs(0).bank_sel.poke(0.U)
        dut.io.pipe_write_reqs(0).pipe_depth.poke(0.U)
        dut.io.pipe_write_reqs(0).oldest.poke(true.B)

        val value = BigInt("deadbeef0123456789abcdef00112233", 16)
        val write = dut.io.pipe_writes(0)
        write.valid.poke(true.B)
        write.bits.eg.poke(3.U)
        write.bits.data.poke(value.U)
        write.bits.mask.poke(((BigInt(1) << 256) - 1).U)
        dut.clock.step()
        write.valid.poke(false.B)
        dut.io.debug.get(3).expect(value.U)

        for (read <- dut.io.read.map(_(0))) {
          read.req.bits.eg.poke(3.U)
          read.req.bits.oldest.poke(false.B)
          read.req.valid.poke(true.B)
          read.req.ready.expect(false.B)
        }
        dut.clock.step()
        for (read <- dut.io.read.map(_(0))) {
          read.req.ready.expect(true.B)
          read.resp.expect(value.U)
        }
        dut.clock.step()
        dut.io.read.foreach(_.foreach(_.req.valid.poke(false.B)))
      }
  }

  it should "commit a low-latency write through the per-bank staging slot" in {
    val base = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    implicit val p: Parameters = EvuVectorParameters(base, 256, 256, 128,
      VectorParams.lgvParams)
    test(new RegisterFile(Seq(1), Seq(1), 1, 1, 4, "vrf_ll"))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.read(0)(0).req.valid.poke(false.B)
        dut.io.mask_read(0)(0).req.valid.poke(false.B)
        dut.io.pipe_write_reqs(0).request.poke(false.B)
        dut.io.pipe_write_reqs(0).fire.poke(false.B)
        dut.io.pipe_write_reqs(0).bank_sel.poke(0.U)
        dut.io.pipe_write_reqs(0).pipe_depth.poke(0.U)
        dut.io.pipe_write_reqs(0).oldest.poke(true.B)

        val value = BigInt("0123456789abcdeffedcba9876543210", 16)
        val llWrite = dut.io.ll_writes(0)
        llWrite.valid.poke(true.B)
        llWrite.bits.eg.poke(5.U)
        llWrite.bits.data.poke(value.U)
        llWrite.bits.mask.poke(((BigInt(1) << 256) - 1).U)
        llWrite.ready.expect(true.B)
        dut.clock.step()
        llWrite.valid.poke(false.B)
        dut.clock.step()

        val read = dut.io.read(0)(0)
        read.req.bits.eg.poke(5.U)
        read.req.bits.oldest.poke(true.B)
        read.req.valid.poke(true.B)
        read.req.ready.expect(false.B)
        dut.clock.step()
        read.req.ready.expect(true.B)
        read.resp.expect(value.U)
      }
  }

}
