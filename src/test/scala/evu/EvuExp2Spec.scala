package evu

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.tile.CoreModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common._
import xiangshan.evu.exu._
import yunsuan.vector.VfExp2Pipe

class EvuExp2PipeHarness(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val flush = Input(Bool())
    val valid = Input(Bool())
    val data = Input(UInt(dLen.W))
    val sew = Input(UInt(2.W))
    val rm = Input(UInt(3.W))
    val mask = Input(UInt(dLenB.W))
    val eg = Input(UInt(log2Ceil(egsTotal).W))
    val vat = Input(UInt(vParams.vatSz.W))
    val result = Output(Valid(new VectorWrite(dLen)))
    val flags = Output(Valid(new VectorFPFlags))
    val completed = Output(Valid(UInt(vParams.vatSz.W)))
  })
  val unit = withReset(reset.asBool || io.flush) {
    Module(new ExecutionUnit(Seq(VFExp2Factory), "Exp2Test"))
  }
  unit.io.iss.valid := io.valid && !io.flush
  unit.io.iss.bits := 0.U.asTypeOf(unit.io.iss.bits)
  unit.io.iss.bits.fu_sel := 1.U
  unit.io.iss.bits.pipe_depth := (VfExp2Pipe.latency - 1).U
  unit.io.iss.bits.rvs2_data := io.data
  unit.io.iss.bits.rvs2_eew := io.sew
  unit.io.iss.bits.rm := io.rm
  unit.io.iss.bits.wmask := io.mask
  unit.io.iss.bits.wvd_eg := io.eg
  unit.io.iss.bits.vat := io.vat
  unit.io.iss.bits.tail := true.B
  unit.io.iter_write.ready := true.B
  unit.io.scalar_write.ready := true.B
  unit.io.shared_fp_req.ready := true.B
  unit.io.shared_fp_resp.valid := false.B
  unit.io.shared_fp_resp.bits := 0.U.asTypeOf(unit.io.shared_fp_resp.bits)
  io.result := unit.io.pipe_write
  io.flags := unit.io.tagged_fflags(0)
  io.completed := unit.io.completed
}

class EvuExp2Spec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Saturn FP16/FP32 Exp2 functional unit"

  private val base = Parameters.empty.alterPartial {
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }
  private implicit val p: Parameters = EvuVectorParameters(base, 512, 256, 128)
  private def single(value: Float): BigInt =
    BigInt(java.lang.Float.floatToRawIntBits(value).toLong & 0xffffffffL)
  private def pack(values: Seq[BigInt], width: Int): BigInt =
    values.zipWithIndex.map { case (value, i) => value << (width * i) }.reduce(_ | _)

  it should "align six-cycle mixed-format results, masks, flags and tags and discard flushed work" in {
    test(new EvuExp2PipeHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      case class Op(sew: Int, in: Seq[BigInt], out: Seq[BigInt], mask: BigInt,
          flags: Int = 0, rm: Int = 0)
      val fullMask = (BigInt(1) << 32) - 1
      val fp32 = Seq(0f, 1f, 2f, -1f, 3f, -2f, 4f, -3f)
      val fp16 = Seq(0x0000, 0x3c00, 0x4000, 0xbc00, 0x4200, 0xc000, 0x4400, 0xc200)
      val fp16Result = Seq(0x3c00, 0x4000, 0x4400, 0x3800, 0x4800, 0x3400, 0x4c00, 0x3000)
      val ops = Seq(
        Op(2, fp32.map(single), fp32.map(x => single(math.pow(2, x.toDouble).toFloat)), fullMask),
        Op(1, (fp16 ++ fp16.reverse).map(BigInt(_)),
          (fp16Result ++ fp16Result.reverse).map(BigInt(_)), fullMask),
        Op(2, Seq.fill(8)(BigInt("7f800001", 16)), Seq.fill(8)(BigInt("7fc00000", 16)), fullMask, 16),
        Op(1, Seq.fill(16)(BigInt(0x7c01)), Seq.fill(16)(BigInt(0x7e00)), BigInt(0)),
        Op(2, Seq.fill(8)(single(128f)), Seq.fill(8)(BigInt("7f800000", 16)), fullMask, 5),
        Op(1, Seq.fill(16)(BigInt(0x4c00)), Seq.fill(16)(BigInt(0x7bff)), fullMask, 5, rm = 1),
        Op(1, Seq.fill(16)(BigInt(0xfc00)), Seq.fill(16)(BigInt(0)), fullMask),
        Op(2, fp32.reverse.map(single), fp32.reverse.map(x => single(math.pow(2, x.toDouble).toFloat)),
          BigInt("00ff0ff0", 16))
      )
      dut.io.flush.poke(false.B)
      dut.io.valid.poke(false.B)
      dut.io.data.poke(0.U)
      dut.io.sew.poke(2.U)
      dut.io.rm.poke(0.U)
      dut.io.mask.poke(fullMask.U)
      dut.io.eg.poke(0.U)
      dut.io.vat.poke(0.U)
      for (cycle <- 0 until ops.size + VfExp2Pipe.latency + 1) {
        val index = cycle - VfExp2Pipe.latency
        dut.io.result.valid.expect((index >= 0 && index < ops.size).B)
        if (index >= 0 && index < ops.size) {
          val op = ops(index)
          dut.io.result.bits.data.expect(pack(op.out, 8 << op.sew).U)
          dut.io.result.bits.eg.expect((index + 4).U)
          val bitMask = (0 until 32).filter(op.mask.testBit).map(i => BigInt(255) << (8 * i))
            .foldLeft(BigInt(0))(_ | _)
          dut.io.result.bits.mask.expect(bitMask.U)
          dut.io.flags.valid.expect(true.B)
          dut.io.flags.bits.flags.expect(op.flags.U)
          dut.io.flags.bits.vat.expect(index.U)
          dut.io.completed.valid.expect(true.B)
          dut.io.completed.bits.expect(index.U)
        }
        dut.io.valid.poke((cycle < ops.size).B)
        if (cycle < ops.size) {
          val op = ops(cycle)
          dut.io.data.poke(pack(op.in, 8 << op.sew).U)
          dut.io.sew.poke(op.sew.U)
          dut.io.rm.poke(op.rm.U)
          dut.io.mask.poke(op.mask.U)
          dut.io.eg.poke((cycle + 4).U)
          dut.io.vat.poke(cycle.U)
        }
        dut.clock.step()
      }
      dut.io.valid.poke(true.B)
      dut.clock.step(2)
      dut.io.valid.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      for (_ <- 0 until VfExp2Pipe.latency + 2) {
        dut.io.result.valid.expect(false.B)
        dut.io.flags.valid.expect(false.B)
        dut.io.completed.valid.expect(false.B)
        dut.clock.step()
      }
    }
  }
}
