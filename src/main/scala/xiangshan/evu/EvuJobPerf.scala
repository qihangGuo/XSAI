package xiangshan.evu

import chisel3._
import chisel3.util._

/** Observe job capacity and ordered completion without changing either handshake. */
class EvuJobPerf(depth: Int, diagnostics: Boolean) extends Module {
  val io = IO(new Bundle {
    val live = Input(Vec(depth, Bool()))
    val completed = Input(Vec(depth, Bool()))
    val head = Input(UInt(log2Ceil(depth).W))
    val headValid = Input(Bool())
    val commandValid = Input(Bool())
    val hasSlot = Input(Bool())
    val retiring = Input(Bool())
    val flush = Input(Bool())
    val full = Output(UInt(64.W))
    val headBlocked = Output(UInt(64.W))
    val fullRelease = Output(UInt(64.W))
  })
  def count(event: Bool): UInt = {
    val counter = RegInit(0.U(64.W))
    when(io.flush) { counter := 0.U }.elsewhen(event) { counter := counter + 1.U }
    counter
  }
  val full = io.commandValid && !io.hasSlot
  io.full := count(full)
  io.headBlocked := 0.U
  io.fullRelease := 0.U
  if (diagnostics) {
    val completedLive = io.live.zip(io.completed).map { case (live, done) => live && done }.reduce(_ || _)
    io.headBlocked := count(io.headValid && !io.completed(io.head) && completedLive)
    io.fullRelease := count(full && io.retiring)
  }
}
