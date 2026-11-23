package xiangshan.evu.exu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu.EvuLowPrecision
import xiangshan.evu.common.VectorConsts
import xiangshan.evu.insns._
import yunsuan.vector.cvt.{ConvertFormat, ConvertParams, ConvertScale, GenericConvert, NativeBF16Convert}

class LowPrecisionInstruction(entry: EvuLowPrecision.Entry) extends VectorInstruction {
  val props = Seq(F6(entry.funct6.U(6.W)), F3(VectorConsts.OPIVV), SEW(2.U), SetsWMask.N) ++
    (if (entry.scaled) Seq(ReadsVS1.Y) else Seq(RS1(entry.subop.get.U(5.W))))
}

/** One layout adapter, three independent physical resources. Numeric latency is
  * imported from the library, including the capture of io.iss. No result padding.
  */
object EvuConvertProvider {
  val raw = ConvertParams(scales = Set(ConvertScale.Raw))
  val mx = ConvertParams(formats = ConvertFormat.floats, scales = Set(ConvertScale.UE8M0))
  def params(family: String): ConvertParams = if (family == "mx") mx else raw
  def latency(family: String): Int =
    if (family == "bf16") NativeBF16Convert.latency else GenericConvert.latency(params(family))
  def instructions(family: String): Seq[VectorInstruction] =
    EvuLowPrecision.entries.filter(_.family == family)
      .map(e => new LowPrecisionInstruction(e).pipelined(latency(family)))
}

case object FPXX8Factory extends FunctionalUnitFactory {
  def insns = EvuConvertProvider.instructions("raw")
  def generate(implicit p: Parameters) = new FPLowPrecisionFU("raw")
}
case object FPMXFPFactory extends FunctionalUnitFactory {
  def insns = EvuConvertProvider.instructions("mx")
  def generate(implicit p: Parameters) = new FPLowPrecisionFU("mx")
}
case object FPBF16ConvertFactory extends FunctionalUnitFactory {
  def insns = EvuConvertProvider.instructions("bf16")
  def generate(implicit p: Parameters) = new FPLowPrecisionFU("bf16")
}

class FPLowPrecisionFU(family: String)(implicit p: Parameters)
    extends PipelinedFunctionalUnit(EvuConvertProvider.latency(family)) {
  require(Set("raw", "mx", "bf16").contains(family))
  require(dLen % 64 == 0)
  val input = io.iss.op
  val output = io.pipe(depth - 1)
  val count = dLen / 32
  val sourceBits = EvuLowPrecision.sourceBits(input.funct6, input.rs1)
  // All instructions process DLEN/32 logical values. Packed inputs retain their
  // actual bit offsets; FP4's eidx is a logical nibble index, never a byte index.
  val sourceOffset = MuxLookup(sourceBits, input.eidx << 5)(Seq(
    4.U -> (input.eidx << 2), 8.U -> (input.eidx << 3), 16.U -> (input.eidx << 4)))
  val source = input.rvs2_data >> sourceOffset(log2Ceil(dLen) - 1, 0)
  val results = Wire(Vec(count, UInt(32.W)))
  val flags = Wire(Vec(count, UInt(5.W)))
  for (lane <- 0 until count) {
    val value = MuxLookup(sourceBits, source(32 * lane + 31, 32 * lane))(Seq(
      4.U -> source(4 * lane + 3, 4 * lane),
      8.U -> source(8 * lane + 7, 8 * lane),
      16.U -> source(16 * lane + 15, 16 * lane)))
    if (family == "bf16") {
      val core = Module(new NativeBF16Convert)
      require(core.latency == depth)
      core.io.fire := io.iss.valid
      core.io.src := value
      core.io.narrow := !EvuLowPrecision.decode(input.funct6, input.rs1)
      core.io.rm := 0.U
      results(lane) := core.io.result
      flags(lane) := core.io.fflags
    } else {
      val core = Module(new GenericConvert(EvuConvertProvider.params(family)))
      require(core.latency == depth)
      core.io.fire := io.iss.valid
      core.io.src := value
      core.io.format := EvuLowPrecision.format(input.funct6, input.rs1)
      core.io.decode := EvuLowPrecision.decode(input.funct6, input.rs1)
      core.io.scaled := (family == "mx").B
      core.io.factor := input.rvs1_data(7, 0)
      core.io.rm := 0.U
      results(lane) := core.io.result
      flags(lane) := core.io.fflags
    }
  }
  val active = VecInit((0 until count).map(lane => output.bits.eidx +& lane.U < output.bits.vl))
  val destinationBits = EvuLowPrecision.destinationBits(output.bits.funct6, output.bits.rs1)
  def packedData(width: Int): UInt = VecInit((0 until count).map { lane =>
    Mux(active(lane), results(lane)(width - 1, 0), 0.U(width.W))
  }).asUInt.pad(dLen)
  val data = MuxLookup(destinationBits, results.asUInt)(Seq(
    4.U -> packedData(4), 8.U -> packedData(8), 16.U -> packedData(16)))
  val pairs = VecInit((0 until count / 2).map(i => active(2 * i) || active(2 * i + 1)))
  val mask = MuxLookup(destinationBits, FillInterleaved(32, active.asUInt))(Seq(
    4.U -> FillInterleaved(8, pairs.asUInt).pad(dLen),
    8.U -> FillInterleaved(8, active.asUInt).pad(dLen),
    16.U -> FillInterleaved(16, active.asUInt).pad(dLen)))
  val destOffset = MuxLookup(destinationBits, output.bits.eidx << 5)(Seq(
    4.U -> (output.bits.eidx << 2), 8.U -> (output.bits.eidx << 3), 16.U -> (output.bits.eidx << 4)))
  val shift = destOffset(log2Ceil(dLen) - 1, 0)
  io.write.valid := output.valid
  io.write.bits.eg := output.bits.wvd_eg
  io.write.bits.data := (data << shift)(dLen - 1, 0)
  io.write.bits.mask := (mask << shift)(dLen - 1, 0)
  io.set_fflags.valid := output.valid
  io.set_fflags.bits := flags.zip(active).map { case (flag, enabled) => Mux(enabled, flag, 0.U) }.reduce(_ | _)
  io.set_vxsat := false.B
  io.stall := false.B
  io.scalar_write.valid := false.B
  io.scalar_write.bits := DontCare
}
