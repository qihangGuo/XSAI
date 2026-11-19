package xiangshan.evu

import chisel3._
import chisel3.util._

/** Memory-only translation records, owned through accepted physical completion. */
class EvuMemoryContextTable(p: EvuParams, tagBits: Int) extends Module {
  private val n = p.memoryCommandDepth
  val io = IO(new Bundle {
    val allocate = Flipped(Valid(new Bundle {
      val tag = UInt(tagBits.W)
      val pageMap = new EvuPageMap(p)
      val virtualBase = UInt(64.W)
    }))
    val release = Flipped(Valid(UInt(tagBits.W)))
    val lookup = Input(UInt(tagBits.W))
    val found = Output(Bool())
    val pageMap = Output(new EvuPageMap(p))
    val virtualBase = Output(UInt(64.W))
    val flush = Input(Bool())
  })
  val valid = RegInit(VecInit.fill(n)(false.B))
  val tags = Reg(Vec(n, UInt(tagBits.W)))
  val maps = Reg(Vec(n, new EvuPageMap(p)))
  val bases = Reg(Vec(n, UInt(64.W)))
  val releasing = VecInit((0 until n).map(i => valid(i) && io.release.valid && tags(i) === io.release.bits))
  val available = ~valid.asUInt | releasing.asUInt
  val chosen = PriorityEncoderOH(available)
  val matches = VecInit((0 until n).map(i => valid(i) && tags(i) === io.lookup))
  io.found := matches.asUInt.orR
  io.pageMap := Mux1H(matches, maps)
  io.virtualBase := Mux1H(matches, bases)
  for (i <- 0 until n) {
    when(releasing(i)) { valid(i) := false.B }
    when(io.allocate.valid && chosen(i)) {
      valid(i) := true.B
      tags(i) := io.allocate.bits.tag
      maps(i) := io.allocate.bits.pageMap
      bases(i) := io.allocate.bits.virtualBase
    }
    when(io.flush) { valid(i) := false.B }
  }
  when(io.allocate.valid && !io.flush) {
    assert(available.orR, "Memory translation table exhausted its existing command credits")
    assert(!(VecInit((0 until n).map(i => valid(i) && !releasing(i) && tags(i) === io.allocate.bits.tag))).asUInt.orR,
      "Duplicate memory translation owner")
  }
  assert(PopCount(matches) <= 1.U)
}
