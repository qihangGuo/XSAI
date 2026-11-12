package xiangshan.evu

import chisel3._
import chisel3.util._

/** Count successful load grants, not cycles spent stalled downstream. */
class EvuVmuRequestArbiter extends Module {
  val io = IO(new Bundle {
    val loadPending = Input(Bool())
    val storePending = Input(Bool())
    val windowPressure = Input(Bool())
    val grant = Input(Bool())
    val flush = Input(Bool())
    val chooseStore = Output(Bool())
  })
  val loadGrants = RegInit(0.U(4.W))
  val limit = Mux(io.windowPressure, 2.U, 8.U)
  io.chooseStore := io.storePending && (!io.loadPending || loadGrants >= limit)
  when(io.flush || !io.storePending || io.grant && io.chooseStore) {
    loadGrants := 0.U
  }.elsewhen(io.grant && io.loadPending && loadGrants < 8.U) {
    loadGrants := loadGrants + 1.U
  }
}

/** Finish RMW Puts promptly, with at most two priority grants while ordinary
  * requests wait. Separate index arbiters retain fairness within each class.
  */
class EvuVmuLineArbiter(p: EvuParams) extends Module {
  private val n = p.maxMemOutstanding
  val io = IO(new Bundle {
    val in = Vec(n, Flipped(Decoupled(new EvuLineMemoryRequest(p))))
    val prepared = Input(Vec(n, Bool()))
    val out = Decoupled(new EvuLineMemoryRequest(p))
    val chosen = Output(UInt(log2Ceil(n).W))
    val flush = Input(Bool())
  })
  val urgent = Module(new RRArbiter(UInt(log2Ceil(n).W), n))
  val regular = Module(new RRArbiter(UInt(log2Ceil(n).W), n))
  for (i <- 0 until n) {
    urgent.io.in(i).valid := io.in(i).valid && io.prepared(i) && !io.flush
    regular.io.in(i).valid := io.in(i).valid && !io.prepared(i) && !io.flush
    urgent.io.in(i).bits := i.U
    regular.io.in(i).bits := i.U
  }
  val streak = RegInit(0.U(2.W))
  val chooseUrgent = urgent.io.out.valid && (!regular.io.out.valid || streak < 2.U)
  io.chosen := Mux(chooseUrgent, urgent.io.out.bits, regular.io.out.bits)
  io.out.valid := Mux(chooseUrgent, urgent.io.out.valid, regular.io.out.valid)
  io.out.bits := Mux1H(UIntToOH(io.chosen, n), io.in.map(_.bits))
  urgent.io.out.ready := io.out.ready && chooseUrgent
  regular.io.out.ready := io.out.ready && !chooseUrgent
  for (i <- 0 until n) {
    io.in(i).ready := io.out.ready && io.out.valid && io.chosen === i.U
  }
  when(io.out.fire && chooseUrgent) {
    assert(io.out.bits.write && io.out.bits.fullLineData, "Prepared RMW request must be a preserved-line Put")
  }
  when(io.flush || !regular.io.out.valid) {
    streak := 0.U
  }.elsewhen(io.out.fire) {
    streak := Mux(chooseUrgent, streak + 1.U, 0.U)
  }
}
