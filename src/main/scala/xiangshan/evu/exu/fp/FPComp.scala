package xiangshan.evu.exu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import xiangshan.evu.common._
import xiangshan.evu.insns._

case object FPCmpFactory extends FunctionalUnitFactory {
  def insns = Seq(
    FMIN.VV, FMIN.VF, FMAX.VV, FMAX.VF,
    FSGNJ.VV, FSGNJ.VF, FSGNJN.VV, FSGNJN.VF, FSGNJX.VV, FSGNJX.VF,
    MFEQ.VV, MFEQ.VF, MFNE.VV, MFNE.VF,
    MFLT.VV, MFLT.VF, MFLE.VV, MFLE.VF,
    MFGT.VF, MFGE.VF,
    FREDMIN.VV, FREDMAX.VV
  ).map(_.restrictSEW(1, 2)).flatten.map(_.pipelined(2))
  def generate(implicit p: Parameters) = new FPCompPipe()(p)
}

class FPCompPipe(implicit p: Parameters) extends PipelinedFunctionalUnit(2)(p) with HasFPUParameters {
  val supported_insns = FPCmpFactory.insns

  io.stall := false.B
  io.set_vxsat := false.B

  val ctrl = new VectorDecoder(io.pipe(0).bits,
    supported_insns, Seq(WritesAsMask, FPComp, FPCompMin, FPMEQ, FPMNE, FPMLT, FPMGT, FPSgnj))

  val ctrl_sgnjn = io.pipe(0).bits.funct6(0)
  val ctrl_sgnjx = io.pipe(0).bits.funct6(1)
  val rvs1_eew = io.pipe(0).bits.rvs1_eew
  val rvs2_eew = io.pipe(0).bits.rvs2_eew
  val rvd_eew  = io.pipe(0).bits.rvd_eew
  val rvs2_data = io.pipe(0).bits.rvs2_data
  val rvs1_data = io.pipe(0).bits.rvs1_data

  val fTypes = Seq(FType.H, FType.S)
  val minmax_results = Wire(Vec(2, UInt(dLen.W)))       // results for vfmin/vfmax
  val comp_results_eew = Seq.tabulate(3)({sew => WireInit(0.U((dLenB >> sew).W))})
  val comp_results = (0 until 3).map({sew => Mux(rvd_eew === sew.U, Fill(1 << sew, comp_results_eew(sew)), 0.U(dLenB.W))}).reduce(_|_)
  val exceptions = Wire(Vec(2, UInt(5.W)))

  for (eew <- 1 to 2) {
    val fType = fTypes(eew-1)
    val num_chunks = dLen / fType.ieeeWidth
    val compare_modules = Seq.fill(num_chunks)(Module(new hardfloat.CompareRecFN(fType.exp, fType.sig)))

    val zip_compares = rvs2_data.asTypeOf(Vec(num_chunks, UInt(fType.ieeeWidth.W))).zip(rvs1_data.asTypeOf(Vec(num_chunks, UInt(fType.ieeeWidth.W)))).zip(compare_modules)

    val gen_compares = zip_compares.map { case((rvs2, rvs1), comp) =>
      val rvs2_rec = fType.recode(rvs2)
      val rvs1_rec = fType.recode(rvs1)
      val rvs2_nan = fType.isNaN(rvs2_rec)
      val rvs1_nan = fType.isNaN(rvs1_rec)

      comp.io.signaling := true.B
      comp.io.a := rvs2_rec
      comp.io.b := rvs1_rec
      (comp.io, rvs2, rvs2_nan, rvs1, rvs1_nan)
    }

    val minmax = gen_compares.map{ case(comp, rvs2, rvs2_nan, rvs1, rvs1_nan) =>
      val minmax_out = Wire(UInt(fType.ieeeWidth.W))
      when(rvs2_nan && rvs1_nan) {
        minmax_out := fType.ieeeQNaN
      } .elsewhen (rvs2_nan) {
        minmax_out := rvs1
      } .elsewhen (rvs1_nan) {
        minmax_out := rvs2
      } .otherwise {
        minmax_out := Mux((!ctrl.bool(FPCompMin) && comp.gt) || (ctrl.bool(FPCompMin) && comp.lt), rvs2, rvs1)
      }
      minmax_out
    }
    minmax_results(eew - 1) := minmax.asUInt

    val comparisons = gen_compares.map{ case(comp, rvs2, rvs2_nan, rvs1, rvs1_nan) =>
      val comparison_out = Wire(UInt(1.W))
      when (ctrl.bool(FPMNE)) {
        when (rvs2_nan || rvs1_nan) {
          comparison_out := 1.U
        } .otherwise {
          comparison_out := !comp.eq
        }
      } .elsewhen (rvs2_nan || rvs1_nan) {
        comparison_out := 0.U
      } .otherwise {
        comparison_out := (comp.eq && ctrl.bool(FPMEQ)) || (comp.lt && ctrl.bool(FPMLT)) || (comp.gt && ctrl.bool(FPMGT))
      }
      comparison_out
    }
    comp_results_eew(eew) := comparisons.asUInt

    exceptions(eew - 1) := gen_compares.map {case(comp, rvs2, rvs2_nan, rvs1, rvs1_nan) => comp.exceptionFlags}.reduce(_ | _)
  }


  val rvs1_vals = rvs1_data.asTypeOf(Vec(dLen / 32, UInt(32.W)))
  val rvs2_vals = rvs2_data.asTypeOf(Vec(dLen / 32, UInt(32.W)))

  // Each word contains one FP32 value or two FP16 values.
  def injectSign(sign1: Bool, sign2: Bool): Bool =
    Mux(ctrl_sgnjn, !sign1, Mux(ctrl_sgnjx, sign1 ^ sign2, sign1))
  val sgnj = rvs2_vals.zip(rvs1_vals).map { case (rvs2, rvs1) =>
    val highSign = injectSign(rvs1(31), rvs2(31))
    val halfSign = Mux(rvd_eew === 1.U, injectSign(rvs1(15), rvs2(15)), rvs2(15))
    highSign ## rvs2(30,16) ## halfSign ## rvs2(14,0)
  }

  val out = Wire(UInt(dLen.W))
  when (ctrl.bool(FPComp)) {
    out := Mux1H(Seq(rvs1_eew === 2.U, rvs1_eew === 1.U),
                 Seq(minmax_results(1), minmax_results(0)))
  } .elsewhen (ctrl.bool(WritesAsMask)) {
    out := Fill(8, comp_results)
  } .otherwise {
    out := sgnj.asUInt
  }

  // Mask writing
  val mask_write_offset = VecInit.tabulate(3)({ eew =>
    Cat(io.pipe(1).bits.eidx(log2Ceil(dLen)-1, dLenOffBits-eew), 0.U((dLenOffBits-eew).W))
  })(io.pipe(1).bits.rvs1_eew)
  val mask_write_mask = (VecInit.tabulate(3)({ eew =>
    VecInit(io.pipe(1).bits.wmask.asBools.grouped(1 << eew).map(_.head).toSeq).asUInt
  })(io.pipe(1).bits.rvs1_eew) << mask_write_offset)(dLen-1,0)

  val s1_writes_as_mask = RegEnable(ctrl.bool(WritesAsMask), io.pipe(0).valid)
  val s1_exceptions = RegEnable(exceptions, io.pipe(0).valid)


  io.write.valid := io.pipe(1).valid
  io.write.bits.eg := io.pipe(1).bits.wvd_eg
  io.write.bits.mask := Mux(s1_writes_as_mask, mask_write_mask, FillInterleaved(8, io.pipe(1).bits.wmask))
  io.write.bits.data := RegEnable(out, io.pipe(0).valid)

  io.set_fflags.valid := io.write.valid
  io.set_fflags.bits := Mux(io.pipe(1).bits.rvd_eew === 2.U, s1_exceptions(1), s1_exceptions(0))
  io.scalar_write.valid := false.B
  io.scalar_write.bits := DontCare
}
