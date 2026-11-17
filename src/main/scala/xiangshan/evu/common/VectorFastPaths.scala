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

  def singleGroupMinMax(inst: BackendIssueInst, datapath: Int, enabled: Boolean): Bool =
    enabled.B && !inst.vmu && inst.funct3 === OPFVV && inst.reduction &&
      inst.opff6.isOneOf(OPFFunct6.fredmin, OPFFunct6.fredmax) &&
      inst.vm && !inst.renvm && inst.vstart === 0.U &&
      inst.vconfig.vtype.vsew === 2.U && !inst.wide_vd && !inst.wide_vs2 &&
      inst.vconfig.vl === (datapath / 32).U && inst.vconfig.vl <= inst.vconfig.vtype.vlMax

  def singleGroupSlide(inst: VectorIssueInst, datapath: Int, enabled: Boolean): Bool = {
    val offset = Mux(inst.funct3 === OPIVX, inst.rs1_data, inst.imm5)
    val elements = (datapath / 8).U >> inst.vconfig.vtype.vsew
    val length = inst.vconfig.vl
    val up = inst.opif6 === OPIFunct6.slideup
    enabled.B && !inst.vmu && inst.funct3.isOneOf(OPIVI, OPIVX) &&
      inst.opif6.isOneOf(OPIFunct6.slideup, OPIFunct6.slidedown) &&
      inst.vm && inst.vstart === 0.U && length =/= 0.U &&
      inst.vconfig.vtype.vsew <= 2.U && length <= elements &&
      length <= inst.vconfig.vtype.vlMax &&
      Mux(up, offset < length,
        (length +& offset) <= elements && (length +& offset) <= inst.vconfig.vtype.vlMax)
  }

  def shiftGroup(data: UInt, offset: UInt, sew: UInt, up: Bool, datapath: Int): UInt = {
    val shift = ((offset << sew) << 3)(log2Ceil(datapath) - 1, 0)
    Mux(up, (data << shift)(datapath - 1, 0), data >> shift)
  }
}
