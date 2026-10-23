package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.RegisterFile
import xiangshan.evu.common.VectorParams

class EvuRegisterFileSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "EVU physical VRF snapshots"

  it should "map all 32 registers through eight banks and preserve masked bytes" in {
    val base = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    // The production EVU is 4096 bits, but the bank/index mapping is
    // scale-invariant. A 512-bit instance keeps Verilator's memory lowering
    // small enough for this focused physical-file test.
    implicit val p: Parameters = EvuVectorParameters(base, 512, 256, 128,
      VectorParams.lgvParams.copy(debugVrf = true))
    test(new RegisterFile(Seq(1), Seq(1), 1, 1, 4, "vrf_snapshot"))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.io.read(0)(0).req.valid.poke(false.B)
        dut.io.mask_read(0)(0).req.valid.poke(false.B)
        val req = dut.io.pipe_write_reqs(0)
        req.request.poke(false.B)
        req.fire.poke(false.B)
        req.bank_sel.poke(0.U)
        req.pipe_depth.poke(0.U)
        req.oldest.poke(true.B)
        dut.io.ll_writes(0).valid.poke(false.B)
        val write = dut.io.pipe_writes(0)
        val mask = (BigInt(1) << 256) - 1
        val values = (0 until 64).map(eg =>
          (0 until 4).map(word => BigInt(0x12340000L + eg * 16 + word) << (word * 64)).reduce(_ | _))
        write.valid.poke(true.B)
        for (eg <- values.indices) {
          write.bits.eg.poke(eg.U)
          write.bits.data.poke(values(eg).U)
          write.bits.mask.poke(mask.U)
          dut.clock.step()
        }
        write.valid.poke(false.B)
        for (eg <- values.indices) dut.io.debug.get(eg).expect(values(eg).U)
        val low = (BigInt(1) << 128) - 1
        write.valid.poke(true.B)
        write.bits.eg.poke(63.U)
        write.bits.data.poke(low.U)
        write.bits.mask.poke(low.U)
        dut.clock.step()
        write.valid.poke(false.B)
        dut.io.debug.get(63).expect(((values(63) & (mask ^ low)) | low).U)
        for (eg <- 0 until 63) dut.io.debug.get(eg).expect(values(eg).U)
      }
  }
}
