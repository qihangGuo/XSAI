package xiangshan.evu.common

import chisel3._
import chisel3.util._
import freechips.rocketchip.util._

object VectorFastPaths extends HasVectorConsts {
  def alignedSlide(inst: VectorIssueInst, datapath: Int, enabled: Boolean): Bool = {
    val offset = Mux(inst.funct3 === OPIVX, inst.rs1_data, inst.imm5)
    val byteBits = log2Ceil(datapath / 8)
    val up = inst.opif6 === OPIFunct6.slideup
    enabled.B && !inst.vmu && inst.funct3.isOneOf(OPIVI, OPIVX) &&
      inst.opif6.isOneOf(OPIFunct6.slideup, OPIFunct6.slidedown) &&
      inst.vm && inst.vstart === 0.U && inst.vconfig.vl =/= 0.U &&
      inst.vconfig.vtype.vsew <= 2.U &&
      (offset << inst.vconfig.vtype.vsew)(byteBits - 1, 0) === 0.U &&
      (inst.vconfig.vl << inst.vconfig.vtype.vsew)(byteBits - 1, 0) === 0.U &&
      (inst.vconfig.vtype.vlMax << inst.vconfig.vtype.vsew)(byteBits - 1, 0) === 0.U &&
      offset < Mux(up, inst.vconfig.vl, inst.vconfig.vtype.vlMax)
  }

  def minMaxTreeDepth(datapath: Int): Int = math.max(2, log2Ceil(datapath / 32))
}
