package xiangshan.evu.backend

import chisel3._
import chisel3.util._

/** A tail may leave a sequencer before earlier writes in other banks commit. */
class WriteCompletionTracker(vatBits: Int, ports: Int) extends Module {
  private val entries = 1 << vatBits
  val io = IO(new Bundle {
    val tails = Input(Vec(ports, Valid(UInt(vatBits.W))))
    val pendingWrites = Input(UInt(entries.W))
    val release = Output(Valid(UInt(vatBits.W)))
    val busy = Output(Bool())
  })
  private val finished = RegInit(0.U(entries.W))
  val arrivals = io.tails.map(t => Mux(t.valid, UIntToOH(t.bits, entries), 0.U(entries.W))).reduce(_ | _)
  val ready = finished & ~io.pendingWrites
  io.release.valid := ready.orR
  io.release.bits := PriorityEncoder(ready)
  finished := (finished | arrivals) & ~Mux(io.release.valid, UIntToOH(io.release.bits, entries), 0.U)
  io.busy := finished.orR
}
