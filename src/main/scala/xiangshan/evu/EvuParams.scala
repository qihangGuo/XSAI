package xiangshan.evu

import chisel3._
import chisel3.util.log2Ceil

/** Parameters for the first external long-vector integration slice. */
case class EvuParams(
    enable:          Boolean = false,
    enableDifftest:   Boolean = false,
    vlenBits:        Int = 4096,
    dlenBits:        Int = 256,
    mlenBits:        Int = 256,
    vectorRegs:      Int = 32,
    lanes:              Int = 4,
    // Ara-style VRF: eight 64-bit banks in each lane.
    vrfBanks:           Int = 8,
    dispatchDepth:      Int = 4,
    loadIssueDepth:     Int = 8,
    storeIssueDepth:    Int = 8,
    executeIssueDepth:  Int = 8,
    specialIssueDepth:  Int = 8,
    loadInflightDepth:  Int = 32,
    loadResponseDepth:  Int = 32,
    maxMemOutstanding:  Int = 8,
    commandDepth:       Int = 8,
    completionDepth:    Int = 8,
    matrixStoreEntries: Int = 16,
    matrixStoreQueueDepth: Int = 8,
    egChaining: Boolean = true,
    vmuLineCoalescing: Boolean = true,
    splitFpIssue: Boolean = true,
    reductionContexts: Int = 2,
    scalarInstructions: Boolean = true
) {
  require(vlenBits > 0 && vlenBits   % 8 == 0)
  require(dlenBits > 0 && dlenBits   % 8 == 0 && vlenBits > dlenBits && vlenBits % dlenBits == 0)
  require(mlenBits >= 16 && mlenBits % 8 == 0 && mlenBits <= dlenBits && dlenBits % mlenBits == 0)
  require(((mlenBits / 8) & ((mlenBits / 8) - 1)) == 0)
  require(vectorRegs == 32, "The xv.* instruction format addresses 32 EVU registers")
  require(lanes > 0 && dlenBits % lanes == 0)
  // Each 64-bit lane slice packs byte, halfword or word elements.
  // Lane width is independent of the maximum 32-bit element width.
  require(dlenBits / lanes == 64, "EVU currently requires 64-bit lane slices")
  require(vrfBanks == 8, "EVU uses eight 64-bit VRF banks per lane")
  require(dispatchDepth > 0 && loadIssueDepth > 0 && storeIssueDepth > 0)
  require(executeIssueDepth > 0 && specialIssueDepth > 0)
  require(loadInflightDepth >= maxMemOutstanding)
  require(loadResponseDepth >= maxMemOutstanding)
  // Matrix and Saturn local tags are remapped onto a shared HBL2 source pool.
  require(maxMemOutstanding >= 8 && (maxMemOutstanding & (maxMemOutstanding - 1)) == 0)
  require(matrixStoreEntries >= 2 && matrixStoreQueueDepth > 0)
  require(commandDepth > 0 && completionDepth > 0)
  require(reductionContexts >= 1 && reductionContexts <= 4)

  val vrfBankCount: Int = vrfBanks
  val vlenBytes:     Int = vlenBits / 8
  val dlenBytes:     Int = dlenBits / 8
  val mlenBytes:     Int = mlenBits / 8
  val memoryTagWidth: Int = log2Ceil(maxMemOutstanding)
  val regIndexWidth: Int = log2Ceil(vectorRegs)
  val laneBits:      Int = dlenBits / lanes
  val translationPages: Int = (8 * vlenBytes + 4095) / 4096 + 1
}

/** Translations checked before retirement, valid for one serialized command. */
class EvuPageMap(p: EvuParams) extends Bundle {
  val enabled = Bool()
  val base = UInt(64.W)
  val length = UInt(32.W)
  val ppn = Vec(p.translationPages, UInt(52.W))
}

object EvuFault {
  val None = 0.U(4.W)
  val Illegal = 1.U(4.W)
  val Access = 2.U(4.W)
  val Page = 3.U(4.W)
  val Misaligned = 4.U(4.W)
  val Capacity = 5.U(4.W)
  val Bus = 6.U(4.W)
  val Flushed = 7.U(4.W)
}

class EvuCommand(p: EvuParams) extends Bundle {
  val pc = UInt(64.W)
  val config = new EvuExecutionConfig(p)
  val opcode = UInt(8.W)
  val sew    = UInt(3.W)
  val vd     = UInt(p.regIndexWidth.W)
  val vs1    = UInt(p.regIndexWidth.W)
  val vs2    = UInt(p.regIndexWidth.W)
  val vl     = UInt(log2Ceil(p.vlenBits + 1).W)
  val base   = UInt(64.W)
  val stride = SInt(32.W)
  val length = UInt(32.W)
  val aux    = UInt(8.W)
  val write  = Bool()
  val chain  = Bool()
  val jobId  = UInt(log2Ceil(p.commandDepth).W)
  val epoch  = UInt(2.W)
  val pageMap = new EvuPageMap(p)
}

class EvuCompletion(p: EvuParams) extends Bundle {
  val pc = UInt(64.W)
  val tval = UInt(64.W)
  val tvalValid = Bool()
  val opcode    = UInt(8.W)
  val vd        = UInt(p.regIndexWidth.W)
  val exception = Bool()
  val fflags    = UInt(5.W)
  val vxsat     = Bool()
  // Context status is published at ordered completion, after scalar retirement.
  val dirtyVs   = Bool()
  val jobId     = UInt(log2Ceil(p.commandDepth).W)
  val epoch     = UInt(2.W)
  // Stable architectural cause snapshot consumed at ordered completion.
  val cause     = UInt(4.W)
}


/** One quantized DLEN beat destined for a CUTE ABMatrixReg entry. */
class EvuCuteWrite(p: EvuParams) extends Bundle {
  val matrixReg = UInt(2.W)
  val bank = UInt(8.W)
  val entry = UInt(16.W)
  val data = UInt(p.dlenBits.W)
  val byteMask = UInt((p.dlenBits / 8).W)
}
