package xiangshan.backend.rob

import chisel3._
import chisel3.util._

/** Preserve scalar backward serialization while allowing retired EVU memory
  * to await its external Ack beside younger scalar instructions. */
class RobEnqueueBarrier extends Module {
  val io = IO(new Bundle {
    val enqueue = Flipped(Valid(Bool())) // true only for ordinary EVU memory
    val robEmpty = Input(Bool())
    val evuBusy = Input(Bool())
    val blocked = Output(Bool())
  })
  private val blocked = RegInit(false.B)
  private val needsEvuDrain = RegInit(true.B)
  when(io.robEmpty && (!needsEvuDrain || !io.evuBusy)) {
    blocked := false.B
  }
  when(io.enqueue.valid) {
    blocked := true.B
    needsEvuDrain := !io.enqueue.bits
  }
  io.blocked := blocked
}
