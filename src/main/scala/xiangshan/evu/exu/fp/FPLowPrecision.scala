package xiangshan.evu.exu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu.EvuLowPrecision
import xiangshan.evu.common.VectorConsts
import xiangshan.evu.insns._
import yunsuan.VfcvtType
import yunsuan.vector.VectorConvert.{CVT_mxfp, CVT_xx8}

class LowPrecisionInstruction(function: Int, scaled: Boolean) extends VectorInstruction {
  val props = Seq(F6(function.U(6.W)), F3(VectorConsts.OPIVV), SEW(2.U), SetsWMask.N) ++
    (if (scaled) Seq(ReadsVS1.Y) else Seq(RS1(0.U(5.W))))
}

case object FPXX8Factory extends FunctionalUnitFactory {
  def insns = (0x38 to 0x3b).map(function => new LowPrecisionInstruction(function, false).pipelined(2))
  def generate(implicit parameters: Parameters) = new FPLowPrecisionFU(false)
}

case object FPMXFPFactory extends FunctionalUnitFactory {
  def insns = (0x3c to 0x3d).map(function => new LowPrecisionInstruction(function, true).pipelined(2))
  def generate(implicit parameters: Parameters) = new FPLowPrecisionFU(true)
}

class E4M3ToF32 extends Module {
  val io = IO(new Bundle {
    val source = Input(UInt(8.W))
    val result = Output(UInt(32.W))
    val flags = Output(UInt(5.W))
  })
  val exponent = io.source(6, 3)
  val fraction = io.source(2, 0)
  val subnormal = MuxLookup(fraction, 0.U(31.W))((1 to 7).map { value =>
    value.U -> (java.lang.Float.floatToRawIntBits(value.toFloat / 512.0f).toLong & 0x7fffffffL).U(31.W)
  })
  val normal = Cat((exponent +& 120.U)(7, 0), fraction, 0.U(20.W))
  val nan = exponent.andR && fraction.andR
  io.result := Mux(nan, "h7fc00000".U, Cat(io.source(7), Mux(exponent === 0.U, subnormal, normal)))
  io.flags := 0.U
}

class FPLowPrecisionFU(mxfp: Boolean)(implicit parameters: Parameters) extends PipelinedFunctionalUnit(2) {
  val packInput = Option.when(mxfp)(IO(Input(Valid(UInt(dLen.W)))))
  val packResult = Option.when(mxfp)(IO(Output(UInt((dLen / 8 + 5).W))))
  require(dLen % 32 == 0)
  val input = io.iss.op
  val output = io.pipe(1)
  val count = dLen / 32
  val inputOffset = (input.eidx << 3)(log2Ceil(dLen) - 1, 0)
  val byteInput = input.rvs2_data >> inputOffset
  val results = Wire(Vec(count, UInt(32.W)))
  val flags = Wire(Vec(count, UInt(5.W)))
  for (lane <- 0 until count) {
    if (mxfp) {
      val converter = Module(new CVT_mxfp)
      converter.io.fire := io.iss.valid || packInput.get.valid
      converter.io.src := Mux(packInput.get.valid, packInput.get.bits, input.rvs2_data)(32 * lane + 31, 32 * lane)
      converter.io.factor := Mux(packInput.get.valid, 127.U, input.rvs1_data(7, 0))
      converter.io.rm := 0.U
      converter.io.isFp4 := packInput.get.valid || EvuLowPrecision.packed(input.funct6)
      results(lane) := converter.io.result
      flags(lane) := converter.io.fflags
    } else {
      val converter = Module(new CVT_xx8)
      val decoder = Module(new E4M3ToF32)
      converter.io.fire := io.iss.valid
      converter.io.src := input.rvs2_data(32 * lane + 31, 32 * lane)
      converter.io.opType := MuxLookup(input.funct6, VfcvtType.vfncvtxx8_int8)(Seq(
        0x39.U -> VfcvtType.vfncvtxx8_e4m3, 0x3a.U -> VfcvtType.vfncvtxx8_e5m2))
      converter.io.rm := 0.U
      decoder.io.source := byteInput(8 * lane + 7, 8 * lane)
      val decodeResult = RegEnable(RegEnable(decoder.io.result, io.iss.valid), io.pipe(0).valid)
      results(lane) := Mux(EvuLowPrecision.decode(output.bits.funct6), decodeResult, converter.io.result)
      flags(lane) := Mux(EvuLowPrecision.decode(output.bits.funct6), 0.U, converter.io.fflags)
    }
  }
  val active = VecInit((0 until count).map(lane => output.bits.eidx +& lane.U < output.bits.vl))
  val decodeOutput = !mxfp.B && EvuLowPrecision.decode(output.bits.funct6)
  val packOutput = mxfp.B && EvuLowPrecision.packed(output.bits.funct6)
  val bytes = VecInit((0 until count).map(lane => Mux(active(lane), results(lane)(7, 0), 0.U(8.W)))).asUInt
  val nibbles = VecInit((0 until count).map(lane => Mux(active(lane), results(lane)(3, 0), 0.U(4.W)))).asUInt
  val byteMask = FillInterleaved(8, active.asUInt)
  val pairMask = VecInit((0 until count / 2).map(pair => active(2 * pair) || active(2 * pair + 1)))
  val destByte = Mux(packOutput, output.bits.eidx >> 1, output.bits.eidx)
  val destShift = (destByte << 3)(log2Ceil(dLen) - 1, 0)
  val narrowData = Mux(packOutput, nibbles.pad(dLen), bytes.pad(dLen))
  val narrowMask = Mux(packOutput, FillInterleaved(8, pairMask.asUInt).pad(dLen), byteMask.pad(dLen))
  io.write.valid := output.valid
  io.write.bits.eg := output.bits.wvd_eg
  io.write.bits.data := Mux(decodeOutput, results.asUInt, (narrowData << destShift)(dLen - 1, 0))
  io.write.bits.mask := Mux(decodeOutput, FillInterleaved(32, active.asUInt),
    (narrowMask << destShift)(dLen - 1, 0))
  io.set_fflags.valid := output.valid
  io.set_fflags.bits := flags.zip(active).map { case (flag, enabled) => Mux(enabled, flag, 0.U) }.reduce(_ | _)
  io.set_vxsat := false.B
  io.stall := packInput.map(_.valid).getOrElse(false.B)
  packResult.foreach(_ := Cat(flags.reduce(_ | _), VecInit(results.map(_(3,0))).asUInt))
  io.scalar_write.valid := false.B
  io.scalar_write.bits := DontCare
}
