package evu

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.tile.CoreModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.AraRegisterFile
import xiangshan.evu.common.{HasVectorParams, VectorParams, VectorWrite}

class EvuVrfBankMappingHarness(implicit p: Parameters) extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val eg = Input(UInt(log2Ceil(egsTotal).W))
    val bank = Output(UInt(vrfBankBits.W))
    val row = Output(UInt(log2Ceil(egsTotal / vParams.vrfBanking).W))
    val writeBank = Output(UInt(vrfBankBits.W))
  })
  val write = WireInit(0.U.asTypeOf(new VectorWrite(dLen)))
  write.eg := io.eg
  io.bank := vrfBank(io.eg)
  io.row := io.eg >> vrfBankBits
  io.writeBank := write.bankId
}

class EvuVrfBankMappingSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU VRF bank mapping"

  private def parameters(vlen: Int, dlen: Int, banks: Int): Parameters = EvuVectorParameters(
    Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, vlen, dlen, dlen, VectorParams.lgvParams.copy(vrfBanking = banks))

  private def bank(eg: Int, groups: Int, banks: Int, folded: Boolean): Int = {
    val reg = eg / groups
    val hash = if (folded) reg ^ (reg >> 2) ^ (reg >> 4) else reg
    if (groups == 1) eg % banks else (eg ^ hash) & (banks - 1)
  }

  for ((vlen, dlen, banks) <- Seq((4096, 512, 4), (1024, 512, 4),
      (4096, 256, 8), (512, 256, 8), (128, 64, 4), (512, 512, 4))) {
    it should s"preserve every row and uniquely map all addresses for VLEN=$vlen DLEN=$dlen banks=$banks" in {
      implicit val p: Parameters = parameters(vlen, dlen, banks)
      val groups = vlen / dlen
      val folded = dlen == 512 && banks == 4
      test(new EvuVrfBankMappingHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        val locations = (0 until groups * 32).map { eg =>
          dut.io.eg.poke(eg.U)
          val expected = bank(eg, groups, banks, folded)
          dut.io.bank.expect(expected.U)
          dut.io.writeBank.expect(expected.U)
          dut.io.row.expect((eg / banks).U)
          (dut.io.row.peek().litValue, dut.io.bank.peek().litValue)
        }
        locations.distinct.size shouldBe groups * 32
        locations.groupBy(_._1).values.foreach(_.map(_._2).toSet shouldBe (0 until banks).map(BigInt(_)).toSet)
        if (folded && groups > 1) {
          for (reg <- 0 until 28; chunk <- 0 until groups) {
            bank(reg * groups + chunk, groups, banks, folded) should not be
              bank((reg + 4) * groups + chunk, groups, banks, folded)
          }
        }
      }
    }
  }

  it should "read paired chunks concurrently and preserve all 512-bit data, byte masks and load commits" in {
    implicit val p: Parameters = parameters(4096, 512, 4)
    test(new AraRegisterFile(Seq(2), Seq.empty, 1, 1, 4, "vrf_bank_fold", trackWrites = true))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        val ports = dut.io.read(0)
        ports.foreach { port =>
          port.req.valid.poke(false.B)
          port.req.bits.eg.poke(0.U)
          port.req.bits.oldest.poke(false.B)
          port.speculative.poke(false.B)
        }
        val reservation = dut.io.pipe_write_reqs(0)
        reservation.request.poke(false.B)
        reservation.fire.poke(false.B)
        reservation.bank_sel.poke(0.U)
        reservation.pipe_depth.poke(0.U)
        reservation.oldest.poke(false.B)
        dut.io.ll_writes(0).valid.poke(false.B)
        dut.io.llOwners.get(0).valid.poke(false.B)
        dut.io.llOwners.get(0).bits.poke(0.U)
        val write = dut.io.pipe_writes(0)
        val mask = (BigInt(1) << 512) - 1
        val data = Array.tabulate(256) { eg =>
          (0 until 16).foldLeft(BigInt(0)) { (bits, lane) =>
            bits | (BigInt(0xa0000000L | (eg << 8) | lane) << (32 * lane))
          }
        }
        for (eg <- data.indices) {
          write.valid.poke(true.B)
          write.bits.eg.poke(eg.U)
          write.bits.data.poke(data(eg).U)
          write.bits.mask.poke(mask.U)
          dut.clock.step()
        }
        write.valid.poke(false.B)

        def readPair(left: Int, right: Int): Unit = {
          for ((port, eg) <- ports.zip(Seq(left, right))) {
            port.req.bits.eg.poke(eg.U)
            port.req.valid.poke(true.B)
          }
          ports.foreach(_.req.ready.expect(true.B))
          dut.clock.step()
          for ((port, eg) <- ports.zip(Seq(left, right))) {
            port.req.valid.poke(false.B)
            port.resp.valid.expect(true.B)
            port.resp.bits.expect(data(eg).U)
          }
          dut.clock.step()
        }
        for (reg <- Seq(0, 1, 2, 3, 8, 9, 10, 11); chunk <- 0 until 8)
          readPair(reg * 8 + chunk, (reg + 4) * 8 + chunk)

        val byteMask = Seq(0, 3, 31, 32, 63).map(byte => BigInt(255) << (8 * byte)).reduce(_ | _)
        write.valid.poke(true.B)
        write.bits.eg.poke(32.U)
        write.bits.data.poke(mask.U)
        write.bits.mask.poke(byteMask.U)
        dut.clock.step()
        write.valid.poke(false.B)
        data(32) = (data(32) & ~byteMask) | byteMask

        val ll = dut.io.ll_writes(0)
        ll.valid.poke(true.B)
        ll.bits.eg.poke(160.U)
        ll.bits.data.poke(123.U)
        ll.bits.mask.poke(mask.U)
        dut.io.llOwners.get(0).valid.poke(true.B)
        dut.io.llOwners.get(0).bits.poke(7.U)
        ll.ready.expect(true.B)
        dut.clock.step()
        ll.valid.poke(false.B)
        val target = bank(160, 8, 4, folded = true)
        for (b <- 0 until 4) {
          dut.io.writeCommits.get(b).valid.expect((b == target).B)
          if (b == target) {
            dut.io.writeCommits.get(b).bits.eg.expect(160.U)
            dut.io.writeCommits.get(b).bits.vat.expect(7.U)
          }
        }
        dut.clock.step()
        data(160) = BigInt(123)
        for (eg <- data.indices) readPair(eg, eg ^ 32)
      }
  }
}
