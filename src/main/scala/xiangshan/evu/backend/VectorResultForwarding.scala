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

class VectorForwardWriter(implicit p: Parameters) extends freechips.rocketchip.tile.CoreBundle with HasVectorParams {
  val vat = UInt(vParams.vatSz.W)
  val writes = UInt(egsTotal.W)
}

class VectorForwardOperand(implicit p: Parameters) extends freechips.rocketchip.tile.CoreBundle with HasVectorParams {
  val eligible = Output(Bool())
  val available = Input(Bool())
}

/** Full-EG result bypass. No cache, extra result slots, or architectural state. */
class VectorResultForwarding(sources: Int, readers: Int, nWriters: Int)(implicit p: Parameters)
    extends CoreModule with HasVectorParams {
  require(sources >= 0 && readers > 0 && nWriters >= 0)
  val io = IO(new Bundle {
    val tail = Input(UInt(vParams.vatSz.W))
    val results = Input(Vec(sources, Valid(new VectorForwardResult)))
    val writers = Input(Vec(nWriters, Valid(new VectorForwardWriter)))
    val reads = Input(Vec(readers, Valid(new PipeHazard(0))))
    val out = Output(Vec(readers, Valid(UInt(dLen.W))))
    val newerWriter = Output(Vec(readers, Bool()))
  })
  if (sources == 0) {
    io.out := 0.U.asTypeOf(io.out)
    io.newerWriter := 0.U.asTypeOf(io.newerWriter)
  } else for (r <- 0 until readers) {
    val read = io.reads(r)
    val matches = io.results.map { result =>
      result.valid && result.bits.mask.andR && result.bits.eg === read.bits.eg &&
        cqOlder(result.bits.vat, read.bits.vat, io.tail)
    }
    val blocked = io.results.map { result =>
      io.writers.map { writer =>
        writer.valid && writer.bits.writes(read.bits.eg) &&
          cqOlder(result.bits.vat, writer.bits.vat, io.tail) &&
          cqOlder(writer.bits.vat, read.bits.vat, io.tail)
      }.foldLeft(false.B)(_ || _)
    }
    val newest = io.results.indices.map { i =>
      !io.results.indices.filter(_ != i).map { j =>
        matches(j) && cqOlder(io.results(i).bits.vat, io.results(j).bits.vat, io.tail)
      }.foldLeft(false.B)(_ || _)
    }
    val selected = VecInit(matches.indices.map(i => matches(i) && !blocked(i) && newest(i)))
    io.out(r).valid := read.valid && selected.asUInt.orR && !reset.asBool
    io.out(r).bits := Mux1H(selected, io.results.map(_.bits.data))
    io.newerWriter(r) := read.valid && VecInit(matches.indices.map(i => matches(i) && blocked(i))).asUInt.orR
    when(io.out(r).valid) { assert(PopCount(selected) === 1.U, "ambiguous full-EG producer") }
  }
}
