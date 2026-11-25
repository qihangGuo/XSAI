package xiangshan.evu.backend

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile.CoreModule
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu.common._
import xiangshan.evu.insns._

/** Whole-register independence for two single-destination, unmasked arithmetic commands. */
class SecondDispatch(paths: Seq[VXIssuePathParams])(implicit p: Parameters)
    extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val head = Input(new VectorIssueInst)
    val second = Input(Valid(new VectorIssueInst))
    val ready = Input(Vec(paths.size, Bool()))
    val take = Output(Bool())
  })
  private object PathId extends XDefaultInstructionField {
    override val width: Int = log2Ceil(paths.size max 2)
  }
  private val excluded = Seq(Reduction, UsesGatherUnit, Wide2VD, Wide2VS2, WritesScalar,
    AlwaysReadsVM, ScalarToVD0, ReadsVS1AsMask, ReadsVS2AsMask, WritesAsMask, UsesNarrowingSext)
  private val table = for {
    (path, i) <- paths.zipWithIndex if path.depth > 0
    op <- path.insns if op.lookup(WritesVD).value == 1 &&
      !excluded.exists(field => op.lookup(field).value != 0)
  } yield op.append(PathId(i.U))

  if (table.nonEmpty && paths.size > 1) {
    def classify(inst: VectorIssueInst) = {
      val dec = new VectorDecoder(inst, table, Seq(PathId, ReadsVS1, ReadsVS2))
      val single = inst.opcode === opcVector && inst.vm && inst.vstart === 0.U &&
        !inst.vconfig.vtype.vill && !inst.vconfig.vtype.vlmul_sign &&
        inst.vconfig.vtype.vlmul_mag === 0.U && inst.emul === 0.U &&
        inst.sew === 2.U && inst.vconfig.vl <= (vLen / 32).U && !inst.fission_vl.valid &&
        !(inst.funct3 === OPIVI && inst.opif6 === OPIFunct6.mvnrr)
      (single && dec.matched, dec.uint(PathId), dec.bool(ReadsVS1), dec.bool(ReadsVS2))
    }
    val a = io.head
    val b = io.second.bits
    val (aValid, aPath, aVs1, aVs2) = classify(a)
    val (bValid, bPath, bVs1, bVs2) = classify(b)
    // A read of vd (e.g. a multiply-accumulate) is covered by the vd/vd check.
    val raw = (bVs1 && a.rd === b.rs1) || (bVs2 && a.rd === b.rs2)
    val war = (aVs1 && b.rd === a.rs1) || (aVs2 && b.rd === a.rs2)
    io.take := io.second.valid && aValid && bValid && aPath =/= bPath &&
      !io.ready(aPath) && io.ready(bPath) && a.rd =/= b.rd && !raw && !war
  } else {
    io.take := false.B
  }
}
