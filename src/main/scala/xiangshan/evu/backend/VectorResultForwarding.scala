package xiangshan.evu.backend

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile.CoreModule
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu.common._

class VectorForwardResult(implicit p: Parameters) extends freechips.rocketchip.tile.CoreBundle with HasVectorParams {
  val eg = UInt(log2Ceil(egsTotal).W)
  val data = UInt(dLen.W)
  val mask = UInt(dLen.W)
  val vat = UInt(vParams.vatSz.W)
}

class RetainedVectorForwardResult(implicit p: Parameters) extends freechips.rocketchip.tile.CoreBundle with HasVectorParams {
  val eg = UInt(log2Ceil(egsTotal).W)
  val data = UInt(dLen.W)
  val byteMask = UInt(dLenB.W)
  val vat = UInt(vParams.vatSz.W)
}

class VectorForwardWriter(implicit p: Parameters) extends freechips.rocketchip.tile.CoreBundle with HasVectorParams {
  val vat = UInt(vParams.vatSz.W)
  val writes = UInt(egsTotal.W)
}

class VectorForwardOperand(implicit p: Parameters) extends freechips.rocketchip.tile.CoreBundle with HasVectorParams {
  val eligible = Output(Bool())
  val mask = Output(UInt(dLen.W))
  val available = Input(Bool())
}

class VectorForwardRead(implicit p: Parameters) extends PipeHazard(0) {
  val mask = UInt(dLen.W)
}

/** Mask-covered bypass; optional bounded recent results never merge old bytes. */
class VectorResultForwarding(sources: Int, readers: Int, nWriters: Int,
    partial: Boolean = false, retainCycles: Int = 0, invalidationPorts: Int = 0)(implicit p: Parameters)
    extends CoreModule with HasVectorParams {
  require(sources >= 0 && readers > 0 && nWriters >= 0 && retainCycles >= 0 && invalidationPorts >= 0)
  val io = IO(new Bundle {
    val tail = Input(UInt(vParams.vatSz.W))
    val results = Input(Vec(sources, Valid(new VectorForwardResult)))
    val writers = Input(Vec(nWriters, Valid(new VectorForwardWriter)))
    val reads = Input(Vec(readers, Valid(new VectorForwardRead)))
    val accepted = Option.when(retainCycles > 0)(Input(Vec(sources, Bool())))
    val invalidate = Input(Vec(invalidationPorts, Valid(UInt(log2Ceil(egsTotal).W))))
    val out = Output(Vec(readers, Valid(UInt(dLen.W))))
    val newerWriter = Output(Vec(readers, Bool()))
  })
  val recent = if (retainCycles > 0 && sources > 0) {
    val data = Reg(Vec(sources, new RetainedVectorForwardResult))
    val remaining = RegInit(VecInit.fill(sources)(0.U(log2Ceil(retainCycles + 1).W)))
    val previousTail = RegNext(io.tail, 0.U)
    val wrapped = io.tail < previousTail
    Seq.tabulate(sources) { i =>
      val changed = io.invalidate.map(w => w.valid && w.bits === data(i).eg).foldLeft(false.B)(_ || _)
      val result = Wire(Valid(new VectorForwardResult))
      result.valid := remaining(i) =/= 0.U && !changed && !wrapped
      result.bits.eg := data(i).eg
      result.bits.data := data(i).data
      result.bits.mask := ByteWriteMask.expand(data(i).byteMask)
      result.bits.vat := data(i).vat
      when(remaining(i) =/= 0.U) { remaining(i) := remaining(i) - 1.U }
      when(changed || wrapped) { remaining(i) := 0.U }
      // Capture accepted writes only; a held iterative result is still a live source.
      when(io.results(i).valid && io.accepted.get(i) && io.results(i).bits.mask.orR) {
        data(i).eg := io.results(i).bits.eg
        data(i).data := io.results(i).bits.data
        data(i).byteMask := ByteWriteMask.pack(io.results(i).bits.mask)
        data(i).vat := io.results(i).bits.vat
        assert(io.results(i).bits.mask === ByteWriteMask.expand(ByteWriteMask.pack(io.results(i).bits.mask)),
          "Retained forwarding result requires whole-byte enables")
        remaining(i) := retainCycles.U
      }
      result
    }
  } else Seq.empty
  val results = io.results.toSeq ++ recent
  if (sources == 0) {
    io.out := 0.U.asTypeOf(io.out)
    io.newerWriter := 0.U.asTypeOf(io.newerWriter)
  } else for (r <- 0 until readers) {
    val read = io.reads(r)
    val matches = results.map { result =>
      result.valid && (if (partial) result.bits.mask.orR else result.bits.mask.andR) &&
        result.bits.eg === read.bits.eg &&
        cqOlder(result.bits.vat, read.bits.vat, io.tail)
    }
    val covered = results.map { result =>
      if (partial) read.bits.mask.orR && (result.bits.mask & read.bits.mask) === read.bits.mask
      else result.bits.mask.andR
    }
    val blocked = results.map { result =>
      io.writers.map { writer =>
        writer.valid && writer.bits.writes(read.bits.eg) &&
          cqOlder(result.bits.vat, writer.bits.vat, io.tail) &&
          cqOlder(writer.bits.vat, read.bits.vat, io.tail)
      }.foldLeft(false.B)(_ || _)
    }
    val newest = results.indices.map { i =>
      !results.indices.filter(_ != i).map { j =>
        matches(j) && (cqOlder(results(i).bits.vat, results(j).bits.vat, io.tail) ||
          (results(i).bits.vat === results(j).bits.vat && (j < i).B))
      }.foldLeft(false.B)(_ || _)
    }
    val selected = VecInit(matches.indices.map(i => matches(i) && covered(i) && !blocked(i) && newest(i)))
    io.out(r).valid := read.valid && selected.asUInt.orR && !reset.asBool
    io.out(r).bits := Mux1H(selected, results.map(_.bits.data)) &
      (if (partial) read.bits.mask else ~0.U(dLen.W))
    io.newerWriter(r) := read.valid && VecInit(matches.indices.map(i => matches(i) && blocked(i))).asUInt.orR
    when(io.out(r).valid) { assert(PopCount(selected) === 1.U, "ambiguous vector producer") }
  }
}
