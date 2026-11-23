package xiangshan.evu.common

import chisel3._
import chisel3.util._
import xiangshan.evu.insns.FEXP2_V
import xiangshan.evu.EvuLowPrecision

/** Diagnostic categories describe executed work, not architectural FLOPs. */
object EvuOpClass {
  val names = Seq("mul", "addsub", "max", "exp2", "fma", "other",
    "e0_mul", "e0_addsub", "e0_max", "e0_exp2", "e0_other",
    "red_sum", "red_max", "red_other", "xx8", "mxfp")
  val width: Int = log2Ceil(names.size)

  def apply(op: ExecuteMicroOp): UInt = {
    val id = WireDefault(5.U(width.W))
    val f6 = op.opff6
    val e0 = !op.acc && op.head && op.tail && op.eidx === 0.U &&
      op.eidx_mask === ((1.U << (1.U << op.vd_eew)) - 1.U)
    when(f6 === OPFFunct6.fmul) { id := Mux(e0, 6.U, 0.U) }
      .elsewhen(f6.isOneOf(OPFFunct6.fadd, OPFFunct6.fsub, OPFFunct6.frsub)) { id := Mux(e0, 7.U, 1.U) }
      .elsewhen(f6 === OPFFunct6.fmax) { id := Mux(e0, 8.U, 2.U) }
      .elsewhen(f6 === OPFFunct6.funary1 && op.rs1 === FEXP2_V.selector.U) { id := Mux(e0, 9.U, 3.U) }
      .elsewhen(f6.isOneOf(OPFFunct6.fmadd, OPFFunct6.fnmadd, OPFFunct6.fmsub, OPFFunct6.fnmsub,
        OPFFunct6.fmacc, OPFFunct6.fnmacc, OPFFunct6.fmsac, OPFFunct6.fnmsac)) { id := Mux(e0, 10.U, 4.U) }
      .elsewhen(e0) { id := 10.U }
    when(op.acc) {
      id := Mux(f6.isOneOf(OPFFunct6.fredusum, OPFFunct6.fredosum, OPFFunct6.fwredusum, OPFFunct6.fwredosum),
        11.U, Mux(f6 === OPFFunct6.fredmax, 12.U, 13.U))
    }
    when(EvuLowPrecision.backend(op.funct3, op.funct6, op.rs1)) {
      id := Mux(EvuLowPrecision.scaledBackend(op.funct6, op.rs1), 15.U, 14.U)
    }
    id
  }

  // The final FU mask is byte-based and already includes tails, predicates and
  // the active half of a reduction fold. Count lanes, not written scalar results.
  def elements(op: ExecuteMicroOpWithData, sew: Int): UInt = {
    val ordinary = PopCount(VecInit((0 until op.wmask.getWidth by (1 << sew)).map { byte =>
      op.wmask(byte + (1 << sew) - 1, byte).andR
    }))
    val lowp = if (sew == 2) PopCount(VecInit((0 until op.wmask.getWidth / 4).map { lane =>
      op.eidx +& lane.U < op.vl
    })) else 0.U
    Mux(EvuLowPrecision.backend(op.funct3, op.funct6, op.rs1), lowp, ordinary)
  }
}

class EvuOpPerf extends Bundle {
  val instructions = UInt(64.W) // First issued micro-op, not queue admission.
  val issues = UInt(64.W)
  val elements16 = UInt(64.W)
  val elements32 = UInt(64.W)
  val dependency = UInt(64.W) // Context-cycles; several contexts may wait together.
  val operands = UInt(64.W)
  val feedback = UInt(64.W)
  val completed = UInt(64.W)
  val latency = UInt(64.W) // Sum of first-issue to architectural write completion.
}

object EvuReductionStage { val names = Seq("stream", "merge", "fold") }
class EvuReductionPerf extends Bundle {
  val issues = UInt(64.W)
  val elements16 = UInt(64.W)
  val elements32 = UInt(64.W)
  val feedback = UInt(64.W)
  val operands = UInt(64.W)
  val resultReadyWait = UInt(64.W)
  val returnIssues = UInt(64.W)
}

class EvuMemoryDiagnostic extends Bundle {
  val loads = UInt(64.W)
  val stores = UInt(64.W)
  val loadBytes = UInt(64.W) // Requested beat bytes; includes load overfetch.
  val storeBytes = UInt(64.W) // Enabled bytes in the logical store mask.
  val logicalLoadBytes = UInt(64.W)
  val logicalStoreBytes = UInt(64.W)
  val noTag = UInt(64.W)
  val downstream = UInt(64.W)
  val loadLost = UInt(64.W)
  val storeLost = UInt(64.W)
  val responseBlocked = UInt(64.W)
  val loadLatency = Vec(4, UInt(64.W)) // Request acceptance to response acceptance.
  val storeLatency = Vec(4, UInt(64.W)) // <16, <64, <256, >=256 cycles.
  val cancelled = UInt(64.W)
}

object EvuReadClass { val names = Seq("fp_fma", "fp_cmp", "fp_exp", "store", "other", "fp_xx8", "fp_mxfp") }
class EvuReadPerf extends Bundle {
  val requests = UInt(64.W)
  val grants = UInt(64.W)
  val readBlocked = UInt(64.W)
  val writeBlocked = UInt(64.W)
  val responseWait = UInt(64.W)
  val held = UInt(64.W)
  val prefetchUsed = UInt(64.W)
  val lastGranted = UInt(64.W)
  val lastBlocked = UInt(64.W)
}
