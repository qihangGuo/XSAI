package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.{AraRegisterFile, RegisterReadCollector}
import xiangshan.evu.common.VectorParams

class EvuSynchronousVrfSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "EVU synchronous 1RW VRF"

  private def parameters: Parameters = EvuVectorParameters(
    Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 512, 256, 128, VectorParams.lgvParams)

  it should "route back-to-back responses using saved grants and preserve individual masked bytes" in {
    implicit val p: Parameters = parameters
    test(new AraRegisterFile(Seq(2, 1), Seq(1), 1, 1, 4, "vrf_synchronous"))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        val ports = Seq(dut.io.read(0)(0), dut.io.read(0)(1), dut.io.read(1)(0), dut.io.mask_read(0)(0))
        ports.foreach { port =>
          port.req.valid.poke(false.B)
          port.req.bits.oldest.poke(false.B)
        }
        dut.io.ll_writes(0).valid.poke(false.B)
        val reservation = dut.io.pipe_write_reqs(0)
        reservation.request.poke(false.B)
        reservation.fire.poke(false.B)
        reservation.bank_sel.poke(0.U)
        reservation.pipe_depth.poke(0.U)
        reservation.oldest.poke(false.B)
        val write = dut.io.pipe_writes(0)
        val fullMask = (BigInt(1) << 256) - 1
        val original = BigInt("0123456789abcdeffedcba9876543210", 16)
        val byteMask = (0 until 32 by 3).map(i => BigInt(255) << (i * 8)).reduce(_ | _)
        val expected = (original & (fullMask ^ byteMask)) | byteMask
        def put(eg: Int, data: BigInt, mask: BigInt = fullMask): Unit = {
          write.valid.poke(true.B)
          write.bits.eg.poke(eg.U)
          write.bits.data.poke(data.U)
          write.bits.mask.poke(mask.U)
          dut.clock.step()
          write.valid.poke(false.B)
        }
        put(3, original)
        put(19, BigInt(0x222))
        put(4, BigInt(0x333))
        put(3, fullMask, byteMask)

        ports(0).req.bits.eg.poke(3.U)
        ports(0).req.valid.poke(true.B)
        ports(0).req.ready.expect(true.B)
        ports.foreach(_.resp.valid.expect(false.B))
        dut.clock.step()
        // The same port changes banks; its previous response must still come from bank 3.
        ports(0).req.bits.eg.poke(4.U)
        ports(1).req.bits.eg.poke(19.U)
        ports(1).req.valid.poke(true.B)
        ports(0).resp.valid.expect(true.B)
        ports(0).resp.bits.expect(expected.U)
        ports(1).resp.valid.expect(false.B)
        ports(0).req.ready.expect(true.B)
        ports(1).req.ready.expect(true.B)
        dut.clock.step()
        ports.foreach(_.req.valid.poke(false.B))
        ports(0).resp.valid.expect(true.B)
        ports(0).resp.bits.expect(0x333.U)
        ports(1).resp.valid.expect(true.B)
        ports(1).resp.bits.expect(0x222.U)
        dut.clock.step()
        ports.foreach(_.resp.valid.expect(false.B))

        // Equal rows from separate first-level groups share one physical read.
        for (port <- Seq(ports(0), ports(2), ports(3))) {
          port.req.bits.eg.poke(3.U)
          port.req.valid.poke(true.B)
          port.req.ready.expect(true.B)
        }
        dut.clock.step()
        ports.foreach(_.req.valid.poke(false.B))
        for (port <- Seq(ports(0), ports(2), ports(3))) {
          port.resp.valid.expect(true.B)
          port.resp.bits.expect(expected.U)
        }
        ports(1).resp.valid.expect(false.B)

        // A prior response may return while the bank accepts a write, but no new read may enter.
        ports(0).req.valid.poke(true.B)
        write.valid.poke(true.B)
        write.bits.eg.poke(3.U)
        write.bits.data.poke(0x444.U)
        write.bits.mask.poke(fullMask.U)
        ports(0).req.ready.expect(false.B)
        dut.clock.step()
        write.valid.poke(false.B)
        ports.foreach(_.resp.valid.expect(false.B))
        ports(0).req.ready.expect(true.B)
        dut.clock.step()
        ports(0).req.valid.poke(false.B)
        ports(0).resp.valid.expect(true.B)
        ports(0).resp.bits.expect(0x444.U)

        dut.clock.step()
        ports(0).req.bits.eg.poke(3.U)
        ports(2).req.bits.eg.poke(19.U)
        ports(3).req.bits.eg.poke(3.U)
        val contenders = Seq(0, 2, 3)
        contenders.foreach(i => ports(i).req.valid.poke(true.B))
        val accepted = contenders.filter(i => ports(i).req.ready.peek().litToBoolean)
        assert(accepted == Seq(2) || accepted == Seq(0, 3))
        dut.clock.step()
        ports.foreach(_.req.valid.poke(false.B))
        for (i <- contenders) {
          ports(i).resp.valid.expect(accepted.contains(i).B)
          if (accepted.contains(i)) ports(i).resp.bits.expect((if (i == 2) 0x222 else 0x444).U)
        }

        dut.clock.step()
        ports(0).req.valid.poke(true.B)
        dut.clock.step()
        ports(0).req.valid.poke(false.B)
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        ports.foreach(_.resp.valid.expect(false.B))
      }
  }

  it should "acknowledge only live matching requests and discard responses on reset" in {
    implicit val p: Parameters = parameters
    test(new RegisterReadCollector).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.client.req.valid.poke(false.B)
      dut.io.prefetch.valid.poke(false.B)
      dut.io.prefetch.bits.eg.poke(0.U)
      dut.io.prefetch.bits.oldest.poke(false.B)
      dut.io.client.req.bits.oldest.poke(false.B)
      dut.io.memory.req.ready.poke(true.B)
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.memory.resp.bits.poke(0x111.U)
      dut.io.client.req.bits.eg.poke(3.U)
      dut.io.client.req.valid.poke(true.B)
      dut.io.client.req.ready.expect(false.B)
      dut.io.memory.req.valid.expect(true.B)
      dut.clock.step()
      dut.io.memory.req.valid.expect(false.B)
      dut.io.memory.resp.valid.poke(true.B)
      dut.io.client.req.bits.eg.poke(4.U)
      dut.io.client.req.ready.expect(false.B)
      // Discard the stale response and launch the changed request together.
      dut.io.memory.req.valid.expect(true.B)
      dut.io.memory.req.bits.eg.expect(4.U)
      dut.clock.step()
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.memory.req.valid.expect(false.B)
      dut.io.client.req.valid.poke(false.B)
      dut.io.memory.resp.valid.poke(true.B)
      dut.io.client.req.ready.expect(false.B)
      dut.clock.step()
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.client.req.valid.poke(true.B)
      dut.io.memory.req.valid.expect(true.B)
      dut.clock.step()
      dut.io.memory.resp.valid.poke(true.B)
      dut.io.memory.resp.bits.poke(0x222.U)
      dut.io.client.req.ready.expect(true.B)
      dut.io.client.resp.expect(0x222.U)
      dut.clock.step()
      dut.io.memory.resp.valid.poke(false.B)
      dut.clock.step()
      dut.reset.poke(true.B)
      dut.io.memory.resp.valid.poke(true.B)
      dut.io.client.req.ready.expect(false.B)
      dut.io.memory.req.valid.expect(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.memory.resp.valid.poke(false.B)
      dut.io.client.req.ready.expect(false.B)
      dut.io.memory.req.valid.expect(true.B)
    }
  }
}
