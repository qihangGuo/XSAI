package xiangshan.evu.common

import chisel3._
import chisel3.util._

/** Select only completed tags; retaining a tag under backpressure stores no payload. */
class CompletionSelector(tagBits: Int) extends Module {
  private val entries = 1 << tagBits
  val io = IO(new Bundle {
    val pending = Input(UInt(entries.W))
    val tail = Input(UInt(tagBits.W))
    val flush = Input(Bool())
    val out = Decoupled(UInt(tagBits.W))
  })
  val locked = RegInit(false.B)
  val held = Reg(UInt(tagBits.W))
  val afterTail = io.pending & (~0.U(entries.W) << io.tail)(entries - 1, 0)
  val chosen = Mux(afterTail.orR, PriorityEncoder(afterTail), PriorityEncoder(io.pending))
  io.out.valid := io.pending.orR && !io.flush
  io.out.bits := Mux(locked, held, chosen)
  when(io.out.valid && !io.out.ready && !locked) {
    locked := true.B
    held := chosen
  }
  when(io.out.fire || io.flush) { locked := false.B }
  when(locked && !io.flush) {
    assert(io.pending(held), "A stalled completion lost its pending tag")
  }
}
