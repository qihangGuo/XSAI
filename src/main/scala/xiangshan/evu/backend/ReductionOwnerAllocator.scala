package xiangshan.evu.backend

import chisel3._
import chisel3.util._

class ReductionOwnerAllocator(contexts: Int) extends Module {
  require(contexts > 0)
  val io = IO(new Bundle {
    val request = Input(Bool())
    val dispatch = Input(Bool())
    val release = Input(UInt(contexts.W))
    val choice = Output(UInt(contexts.W))
    val blocked = Output(Bool())
    val fullWithRelease = Output(Bool())
    val reused = Output(Bool())
    val allocated = Output(UInt(contexts.W))
  })
  val allocated = RegInit(0.U(contexts.W))
  val available = ~allocated | io.release
  io.choice := PriorityEncoderOH(available)
  io.blocked := io.request && !available.orR
  val allocate = Mux(io.request && io.dispatch, io.choice, 0.U(contexts.W))
  allocated := (allocated & ~io.release) | allocate
  io.allocated := allocated
  io.fullWithRelease := io.request && allocated.andR && io.release.orR
  io.reused := (allocate & allocated & io.release).orR
  assert(!(io.request && io.dispatch && !available.orR), "Reduction dispatched without an owner")
}
