package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.VectorResultForwarding
import xiangshan.evu.common._
import xiangshan.evu.exu._

class EvuIterForwardingHarness(implicit parameters: Parameters) extends Module {
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val ready = Input(Bool())
    val mask = Input(UInt(64.W))
    val resultValid = Output(Bool())
    val forwardable = Output(Bool())
    val bypass = Output(Bool())
    val data = Output(UInt(512.W))
    val completed = Output(Bool())
  })
  val unit = Module(new ExecutionUnit(Seq(SIMDFPDivSqrtFactory), "iter_forward_test"))
  unit.io.iss.valid := io.valid
  unit.io.iss.bits := 0.U.asTypeOf(unit.io.iss.bits)
  unit.io.iss.bits.funct3 := VectorConsts.OPFVV
  unit.io.iss.bits.funct6 := OPFFunct6.fdiv.asUInt
  unit.io.iss.bits.fu_sel := 1.U
  unit.io.iss.bits.rvs1_eew := 2.U
  unit.io.iss.bits.rvs2_eew := 2.U
  unit.io.iss.bits.vd_eew := 2.U
  unit.io.iss.bits.rvs1_data := Fill(16, "h40000000".U(32.W))
  unit.io.iss.bits.rvs2_data := Fill(16, "h40800000".U(32.W))
  unit.io.iss.bits.wmask := io.mask
  unit.io.iss.bits.tail := true.B
  unit.io.iss.bits.vat := 1.U
  unit.io.iss.bits.wvd_eg := 8.U
  unit.io.iter_write.ready := io.ready
  unit.io.scalar_write.ready := true.B
  unit.io.shared_fp_req.ready := true.B
  unit.io.shared_fp_resp := 0.U.asTypeOf(unit.io.shared_fp_resp)
  val forwarding = Module(new VectorResultForwarding(1, 1, 0))
  forwarding.io.tail := 0.U
  forwarding.io.results(0).valid := unit.io.iter_write.valid && unit.io.iterWriteForwardable
  forwarding.io.results(0).bits.eg := unit.io.iter_write.bits.eg
  forwarding.io.results(0).bits.data := unit.io.iter_write.bits.data
  forwarding.io.results(0).bits.mask := unit.io.iter_write.bits.mask
  forwarding.io.results(0).bits.vat := unit.io.iterWriteVat
  forwarding.io.reads(0).valid := true.B
  forwarding.io.reads(0).bits.eg := 8.U
  forwarding.io.reads(0).bits.vat := 2.U
  io.resultValid := unit.io.iter_write.valid
  io.forwardable := unit.io.iterWriteForwardable
  io.bypass := forwarding.io.out(0).valid
  io.data := forwarding.io.out(0).bits
  io.completed := unit.io.completed.valid
}

class EvuIterForwardingSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private implicit val parameters: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }, 4096, 512, 512, VectorParams.lgvParams.copy(parallelFp32Div = true))

  behavior of "Iterative full-group result forwarding"

  it should "forward a held full divide result but reject partial groups and complete only on acceptance" in {
    test(new EvuIterForwardingHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.valid.poke(false.B)
      dut.io.ready.poke(false.B)
      for (active <- Seq(16, 13, 1)) {
        dut.io.mask.poke(((BigInt(1) << (4 * active)) - 1).U)
        dut.io.valid.poke(true.B)
        dut.clock.step()
        dut.io.valid.poke(false.B)
        var cycles = 0
        while (!dut.io.resultValid.peek().litToBoolean && cycles < 80) {
          dut.clock.step()
          cycles += 1
        }
        cycles should be < 80
        for (_ <- 0 until 3) {
          dut.io.resultValid.expect(true.B)
          dut.io.forwardable.expect((active == 16).B)
          dut.io.bypass.expect((active == 16).B)
          if (active == 16) {
            dut.io.data.expect((BigInt("40000000", 16) * ((BigInt(1) << 512) - 1) /
              ((BigInt(1) << 32) - 1)).U)
          }
          dut.io.completed.expect(false.B)
          dut.clock.step()
        }
        dut.io.ready.poke(true.B)
        dut.io.completed.expect(true.B)
        dut.clock.step()
        dut.io.ready.poke(false.B)
        dut.io.resultValid.expect(false.B)
      }
    }
  }
}
