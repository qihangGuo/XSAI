package xiangshan.evu.backend

import chisel3._
import chisel3.util._
import xiangshan.evu.common.CompletionSelector

/** A tail may leave a sequencer before earlier writes in other banks commit. */
class WriteCompletionTracker(vatBits: Int, ports: Int, diagnostics: Boolean = false) extends Module {
  private val entries = 1 << vatBits
  val io = IO(new Bundle {
    val tails = Input(Vec(ports, Valid(UInt(vatBits.W))))
    val pendingWrites = Input(UInt(entries.W))
    val release = Output(Valid(UInt(vatBits.W)))
    val busy = Output(Bool())
    val tail = Input(UInt(vatBits.W))
    val reportHead = Option.when(diagnostics)(Input(Valid(UInt(vatBits.W))))
    val headWriteWait = Output(Bool())
    val headTrackerWait = Output(Bool())
  })
  private val finished = RegInit(0.U(entries.W))
  val arrivals = io.tails.map(t => Mux(t.valid, UIntToOH(t.bits, entries), 0.U(entries.W))).reduce(_ | _)
  val ready = finished & ~io.pendingWrites
  val selector = Module(new CompletionSelector(vatBits))
  selector.io.pending := ready
  selector.io.tail := io.tail
  selector.io.flush := reset.asBool
  selector.io.out.ready := true.B
  io.release.valid := selector.io.out.valid
  io.release.bits := selector.io.out.bits
  io.headWriteWait := false.B
  io.headTrackerWait := false.B
  io.reportHead.foreach { head =>
    io.headWriteWait := head.valid && finished(head.bits) && io.pendingWrites(head.bits)
    io.headTrackerWait := head.valid && ready(head.bits) && io.release.bits =/= head.bits
  }
  finished := (finished | arrivals) & ~Mux(io.release.valid, UIntToOH(io.release.bits, entries), 0.U)
  io.busy := finished.orR
}
