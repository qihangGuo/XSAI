package xiangshan.evu.exu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu.insns.FEXP2_V
import yunsuan.VfexpType
import yunsuan.vector.{VfExp2, VfExp2Pipe}

case object VFExp2Factory extends FunctionalUnitFactory {
  def insns = FEXP2_V.restrictSEW(1, 2).map(_.pipelined(VfExp2Pipe.latency))
  def generate(implicit p: Parameters) = new VFExp2FU
}

class VFExp2FU(implicit p: Parameters) extends PipelinedFunctionalUnit(VfExp2Pipe.latency) {
  require(dLen % 64 == 0)

  val units = Seq.fill(dLen / 64)(Module(new VfExp2(64)))
  for ((unit, i) <- units.zipWithIndex) {
    unit.io.fire := io.iss.valid
    unit.io.src := io.iss.op.rvs2_data(64 * (i + 1) - 1, 64 * i)
    unit.io.opType := VfexpType.vfexp2
    unit.io.sew := io.iss.op.rvs2_eew
    unit.io.rm := io.iss.op.frm
  }

  val out = io.pipe(depth - 1)
  io.write.valid := out.valid
  io.write.bits.eg := out.bits.wvd_eg
  io.write.bits.mask := FillInterleaved(8, out.bits.wmask)
  io.write.bits.data := Cat(units.reverse.map(_.io.result))

  // YunSuan packs four FP16 flags, or two FP32 flags, into each 64-bit slice.
  val flags = units.zipWithIndex.flatMap { case (unit, slice) =>
    val elementFlags = unit.io.fflags.asTypeOf(Vec(4, UInt(5.W)))
    (0 until 4).map { element =>
      val fp16Active = out.bits.wmask(slice * 8 + element * 2)
      val fp32Active = if (element < 2) out.bits.wmask(slice * 8 + element * 4) else false.B
      Mux(Mux(out.bits.rvs2_eew === 2.U, fp32Active, fp16Active), elementFlags(element), 0.U)
    }
  }
  io.set_fflags.valid := out.valid
  io.set_fflags.bits := flags.reduce(_ | _)
  io.stall := false.B
  io.set_vxsat := false.B
  io.scalar_write.valid := false.B
  io.scalar_write.bits := DontCare
}
