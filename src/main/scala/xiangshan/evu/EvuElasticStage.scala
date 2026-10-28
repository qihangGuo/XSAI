package xiangshan.evu

import chisel3._
import chisel3.util._

/** One elastic register, with simultaneous consume/replace and flush priority. */
class EvuElasticStage[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(gen))
    val out = Decoupled(gen)
    val flush = Input(Bool())
  })
  val valid = RegInit(false.B)
  val data = Reg(gen)
  io.in.ready := (!valid || io.out.ready) && !io.flush
  io.out.valid := valid && !io.flush
  io.out.bits := data
  when(io.in.ready) {
    valid := io.in.valid
    when(io.in.valid) { data := io.in.bits }
  }
  when(io.flush) { valid := false.B }
}
