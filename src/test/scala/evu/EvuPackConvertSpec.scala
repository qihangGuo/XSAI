package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import evu.cvtref.LowpReference
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common._
import xiangshan.evu.exu._

class EvuPackConvertHarness(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val issue = Input(Bool())
    val funct6 = Input(UInt(6.W))
    val factor = Input(UInt(8.W))
    val source = Input(UInt(64.W))
    val tag = Input(UInt(4.W))
    val packing = Flipped(Valid(UInt(64.W)))
    val ready = Output(Bool())
    val packed = Output(UInt(13.W))
    val write = Output(Valid(new VectorWrite(64)))
    val flags = Output(Valid(UInt(5.W)))
    val vat = Output(UInt(4.W))
  })
  val unit = Module(new ExecutionUnit(Seq(FPMXFPFactory), "pack_convert_test"))
  unit.io.iss.valid := io.issue
  unit.io.iss.bits := 0.U.asTypeOf(unit.io.iss.bits)
  val op = unit.io.iss.bits
  op.fu_sel := 1.U
  op.funct3 := VectorConsts.OPIVV
  op.funct6 := io.funct6
  op.rvs1_data := io.factor
  op.rvs2_data := io.source
  op.vl := 2.U
  op.vm := true.B
  op.eidx_mask := 255.U
  op.wmask := 255.U
  op.pipe_depth := 1.U
  op.vat := io.tag
  op.wvd_eg := io.tag
  op.tail := true.B
  unit.packIn.get := io.packing
  unit.io.iter_write.ready := true.B
  unit.io.scalar_write.ready := true.B
  unit.io.shared_fp_req.ready := true.B
  unit.io.shared_fp_resp := 0.U.asTypeOf(unit.io.shared_fp_resp)
  io.ready := unit.io.iss.ready
  io.packed := unit.packedOut.get
  io.write := unit.io.pipe_write
  io.flags := unit.io.set_fflags
  io.vat := unit.io.pipeWriteVat
}

class EvuPackConvertSpec extends AnyFlatSpec with ChiselScalatestTester {
  private implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }, 4096, 64, 64)

  it should "arbitrate PACK with MX conversion and preserve two-edge results, flags and write tags" in {
    test(new EvuPackConvertHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      def fp(value: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(value).toLong & 0xffffffffL)
      def pair(low: BigInt, high: BigInt): BigInt = low | (high << 32)
      def packExpected(low: BigInt, high: BigInt): BigInt = {
        val values = Seq(low, high).map(LowpReference.convert(_, LowpReference.E2M1,
          decode = false, scaled = false, factor = 127, rm = 0))
        values.head.bits | (values(1).bits << 4) | (BigInt(values.map(_.flags).reduce(_ | _)) << 8)
      }
      def ordinary(funct6: Int, factor: Int, source: BigInt, tag: Int): Unit = {
        dut.io.issue.poke(true.B)
        dut.io.funct6.poke(funct6.U)
        dut.io.factor.poke(factor.U)
        dut.io.source.poke(source.U)
        dut.io.tag.poke(tag.U)
      }
      def expectWrite(value: BigInt, tag: Int): Unit = {
        dut.io.write.valid.expect(true.B)
        dut.io.write.bits.data.expect(value.U)
        dut.io.write.bits.mask.expect(((BigInt(1) << 64) - 1).U)
        dut.io.write.bits.eg.expect(tag.U)
        dut.io.vat.expect(tag.U)
        dut.io.flags.valid.expect(true.B)
        dut.io.flags.bits.expect(0.U)
      }
      dut.io.issue.poke(false.B)
      dut.io.funct6.poke(0.U)
      dut.io.factor.poke(255.U)
      dut.io.source.poke(0.U)
      dut.io.tag.poke(0.U)
      dut.io.packing.valid.poke(false.B)
      dut.io.packing.bits.poke(0.U)
      dut.clock.step(2)

      ordinary(0x32, 128, 0x3838, 1) // E4M3 1.0 times scale 2.
      dut.io.ready.expect(true.B)
      dut.clock.step()
      dut.io.write.valid.expect(false.B)

      // Hold an E5M2 decode behind two PACK beats. Poison its scale while
      // packing so a missing PACK control override changes data or flags.
      ordinary(0x33, 255, 0x3c3c, 2)
      dut.io.packing.valid.poke(true.B)
      dut.io.packing.bits.poke(pair(fp(1.25f), fp(7.0f)).U)
      dut.io.ready.expect(false.B)
      dut.clock.step()
      expectWrite(pair(fp(2.0f), fp(2.0f)), 1)

      dut.io.packing.bits.poke(pair(BigInt("7fc00000", 16), fp(-0.75f)).U)
      dut.io.ready.expect(false.B)
      dut.clock.step()
      dut.io.packed.expect(packExpected(fp(1.25f), fp(7.0f)).U)
      dut.io.write.valid.expect(false.B)
      dut.io.flags.valid.expect(false.B)

      dut.io.packing.valid.poke(false.B)
      ordinary(0x33, 129, 0x3c3c, 2) // E5M2 1.0 times scale 4.
      dut.io.ready.expect(true.B)
      dut.clock.step()
      dut.io.packed.expect(packExpected(BigInt("7fc00000", 16), fp(-0.75f)).U)
      dut.io.write.valid.expect(false.B)
      dut.io.flags.valid.expect(false.B)

      dut.io.issue.poke(false.B)
      dut.io.funct6.poke(0.U)
      dut.io.factor.poke(255.U)
      dut.clock.step()
      expectWrite(pair(fp(4.0f), fp(4.0f)), 2)
      dut.clock.step(2)
      dut.io.write.valid.expect(false.B)
      dut.io.flags.valid.expect(false.B)
      println("EVU_PACK_CONVERT_PASS packBeats=2 normalWrites=2 blockedIssues=2 latency=2")
    }
  }
}
