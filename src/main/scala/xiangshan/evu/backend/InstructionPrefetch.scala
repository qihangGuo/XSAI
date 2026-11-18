package xiangshan.evu.backend

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile.CoreModule
import freechips.rocketchip.util._
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu.common._
import xiangshan.evu.insns._

/** First-read hints for the queue head; normal reads and invalidation remain authoritative. */
class InstructionPrefetch(insns: Seq[VectorInstruction], nWriters: Int,
    fallback: Boolean = false)(implicit p: Parameters) extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val next = Input(Valid(new BackendIssueInst))
    val accepts = Input(Bool())
    val atTail = Input(Bool())
    val vatHead = Input(UInt(vParams.vatSz.W))
    val vatTail = Input(UInt(vParams.vatSz.W))
    val writers = Input(Vec(nWriters, Valid(new VectorForwardWriter)))
    val hints = Output(Vec(3, Valid(new VectorReadReq)))
    val slide = Output(Bool())
  })
  val next = io.next.bits
  val ctrl = new VectorDecoder(next, insns, Seq(UsesGatherUnit))
  val aligned = VectorFastPaths.alignedSlide(next, dLen, vParams.alignedSlide)
  val group = VectorFastPaths.singleGroupSlide(next, dLen, vParams.singleGroupSlide)
  val directSlide = vParams.slidePrefetch.B && (aligned || group)
  val candidate = io.next.valid && io.accepts && next.vm && next.vstart === 0.U &&
    next.vconfig.vl =/= 0.U && !next.reduction &&
    (!ctrl.bool(UsesGatherUnit) || directSlide) && io.atTail &&
    (vParams.crossInstructionPrefetch.B || (fallback.B && !next.scalar_to_vd0 &&
      next.vconfig.vtype.vsew === 2.U && next.vconfig.vl >= (dLen / 32).U))
  val scalarGather = next.funct3.isOneOf(OPIVI, OPIVX) && next.opif6 === OPIFunct6.rgather
  val offset = Mux(next.funct3 === OPIVX, next.rs1_data, next.imm5)
  // An aligned slide-up starts writing at offset, but its first source is E0.
  val slideSource = Mux(aligned && next.opif6 === OPIFunct6.slidedown, offset, 0.U)
  val sourceIndex = Mux(scalarGather, offset, Mux(directSlide, slideSource, 0.U))
  val enabled = Seq(next.renv1 && !next.reads_vs1_mask,
    next.renv2 && !next.reads_vs2_mask, next.renvd)
  val registers = Seq(next.rs1, next.rs2, next.rd)
  for (port <- 0 until 3) {
    val index = if (port == 1) sourceIndex else 0.U
    val eg = Wire(UInt(log2Ceil(egsTotal).W))
    eg := getEgId(registers(port), index, next.vconfig.vtype.vsew, false.B)
    val olderWriter = io.writers.map { writer =>
      writer.valid && writer.bits.writes(eg) && cqOlder(writer.bits.vat, next.vat, io.vatTail)
    }.foldLeft(false.B)(_ || _)
    val inRange = if (port == 1) index < next.vconfig.vtype.vlMax else true.B
    io.hints(port).valid := candidate && enabled(port) && inRange && !olderWriter &&
      (vParams.crossInstructionPrefetch.B || (port == 1).B)
    io.hints(port).bits.eg := eg
    io.hints(port).bits.oldest := next.vat === io.vatHead
    io.hints(port).bits.lastOperand := false.B
  }
  io.slide := directSlide && io.hints.map(_.valid).reduce(_ || _)
}
