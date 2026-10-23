package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.{TreadleBackendAnnotation, VerilatorBackendAnnotation}
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.AraRegisterFile
import xiangshan.evu.common.VectorParams

class EvuByteMaskVrfSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "EVU byte-masked SRAM"

  private def parameters: Parameters = EvuVectorParameters(
    Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 512, 256, 128, VectorParams.lgvParams.copy(debugVrf = true))

  private def initialize(dut: AraRegisterFile): Unit = {
    dut.io.read(0)(0).req.valid.poke(false.B)
    dut.io.mask_read(0)(0).req.valid.poke(false.B)
    dut.io.pipe_writes(0).valid.poke(false.B)
    dut.io.ll_writes(0).valid.poke(false.B)
    val req = dut.io.pipe_write_reqs(0)
    req.request.poke(false.B)
    req.fire.poke(false.B)
    req.bank_sel.poke(0.U)
    req.pipe_depth.poke(0.U)
    req.oldest.poke(false.B)
  }

  it should "preserve every disabled byte for pipe and staged LL writes across all four lanes" in {
    implicit val p: Parameters = parameters
    test(new AraRegisterFile(Seq(1), Seq(1), 1, 1, 4, "vrf_bytes"))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        initialize(dut)
        val fullMask = (BigInt(1) << 256) - 1
        var expected = (0 until 32).map(i => BigInt(i * 7 + 1) << (i * 8)).reduce(_ | _)

        def write(data: BigInt, mask: BigInt, ll: Boolean): Unit = {
          if (ll) {
            val port = dut.io.ll_writes(0)
            port.valid.poke(true.B)
            port.bits.eg.poke(7.U)
            port.bits.data.poke(data.U)
            port.bits.mask.poke(mask.U)
            port.ready.expect(true.B)
            dut.clock.step()
            port.valid.poke(false.B)
            dut.clock.step()
          } else {
            val port = dut.io.pipe_writes(0)
            port.valid.poke(true.B)
            port.bits.eg.poke(7.U)
            port.bits.data.poke(data.U)
            port.bits.mask.poke(mask.U)
            dut.clock.step()
            port.valid.poke(false.B)
          }
          expected = (expected & (fullMask ^ mask)) | (data & mask)
          dut.io.debug.get(7).expect(expected.U)
          val ports = Seq(dut.io.read(0)(0), dut.io.mask_read(0)(0))
          ports.foreach { port =>
            port.req.bits.eg.poke(7.U)
            port.req.bits.oldest.poke(true.B)
            port.req.valid.poke(true.B)
            port.req.ready.expect(true.B)
          }
          dut.clock.step()
          ports.foreach { port =>
            port.req.valid.poke(false.B)
            port.resp.valid.expect(true.B)
            port.resp.bits.expect(expected.U)
          }
          dut.clock.step()
          ports.foreach(_.resp.valid.expect(false.B))
        }

        write(expected, fullMask, ll = false)
        for (byte <- 0 until 32) {
          val mask = BigInt(255) << (byte * 8)
          write(fullMask, mask, ll = false)
          write(0, mask, ll = true)
        }
        val alternating = (0 until 32 by 2).map(i => BigInt(255) << (i * 8)).reduce(_ | _)
        write(fullMask, alternating, ll = false)
        write(BigInt("5a" * 32, 16), fullMask ^ alternating, ll = true)
        write(fullMask, 0, ll = false)
        write(0, 0, ll = true)
      }
  }

  for (ll <- Seq(false, true)) {
    it should s"reject a partial-bit ${if (ll) "LL" else "pipe"} write instead of widening its mask" in {
      implicit val p: Parameters = parameters
      intercept[ChiselAssertionError] {
        test(new AraRegisterFile(Seq(1), Seq(1), 1, 1, 4, s"vrf_partial_bit_${if (ll) "ll" else "pipe"}"))
          .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
            initialize(dut)
            if (ll) {
              val port = dut.io.ll_writes(0)
              port.valid.poke(true.B)
              port.bits.eg.poke(7.U)
              port.bits.data.poke(255.U)
              port.bits.mask.poke(1.U)
              dut.clock.step()
              port.valid.poke(false.B)
            } else {
              val port = dut.io.pipe_writes(0)
              port.valid.poke(true.B)
              port.bits.eg.poke(7.U)
              port.bits.data.poke(255.U)
              port.bits.mask.poke(1.U)
            }
            dut.clock.step()
          }
      }
    }
  }
}
