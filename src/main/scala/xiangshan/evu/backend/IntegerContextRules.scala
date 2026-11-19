package xiangshan.evu.backend

import chisel3._
import chisel3.util._
import freechips.rocketchip.util._
import xiangshan.evu.common._
import xiangshan.evu.insns._

/** Instructions that never consume the shared permutation stream. */
object IntegerContextRules extends HasVectorConsts {
  def instructions(insns: Seq[VectorInstruction]): Seq[VectorInstruction] = insns.filter { i =>
    val f3 = i.lookup(F3).value
    val f6 = i.lookup(F6).value
    val arithmetic = Seq(OPIVV.litValue, OPIVX.litValue, OPIVI.litValue).contains(f3) &&
      Seq(OPIFunct6.min.litValue, OPIFunct6.minu.litValue, OPIFunct6.sub.litValue, OPIFunct6.and.litValue).contains(f6)
    val permute = Seq(OPIVX.litValue, OPIVI.litValue).contains(f3) &&
      Seq(OPIFunct6.rgather.litValue, OPIFunct6.slideup.litValue, OPIFunct6.slidedown.litValue).contains(f6)
    i.lookup(SEW).value == 2 && (arithmetic || permute)
  }
  def accepts(inst: VectorIssueInst, datapath: Int, aligned: Boolean, group: Boolean): Bool = {
    val t = inst.vconfig.vtype
    val offset = Mux(inst.funct3 === OPIVX, inst.rs1_data, inst.imm5)
    val gather = inst.funct3.isOneOf(OPIVI, OPIVX) && inst.opif6 === OPIFunct6.rgather &&
      offset < t.vlMax
    val arithmetic = inst.opif6.isOneOf(OPIFunct6.min, OPIFunct6.minu, OPIFunct6.sub, OPIFunct6.and)
    val slide = VectorFastPaths.alignedSlide(inst, datapath, aligned) ||
      VectorFastPaths.singleGroupSlide(inst, datapath, group)
    !inst.vmu && inst.vm && inst.vstart === 0.U && t.vsew === 2.U &&
      !t.vlmul_sign && t.vlmul_mag === 0.U && inst.vconfig.vl =/= 0.U &&
      inst.vconfig.vl <= (datapath / 32).U && inst.vconfig.vl <= t.vlMax &&
      (gather || arithmetic || slide)
  }

  def raw(reads: UInt, olderWrites: UInt): Bool = (reads & olderWrites).orR
  def destination(writes: UInt, olderReads: UInt, olderWrites: UInt): Bool =
    (writes & (olderReads | olderWrites)).orR
}
