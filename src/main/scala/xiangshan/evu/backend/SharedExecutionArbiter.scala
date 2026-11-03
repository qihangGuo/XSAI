package xiangshan.evu.backend

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile.CoreModule
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu.common._

/** Logical sequencers share a FU result channel, including mixed latencies. */
class SharedExecutionArbiter(contexts: Int, maxDepth: Int, nFUs: Int,
    prioritizeCritical: Boolean = false)(implicit p: Parameters)
    extends CoreModule {
  require(contexts > 0 && maxDepth > 0)
  private val depthBits = log2Ceil(maxDepth) max 1
  val io = IO(new Bundle {
    val in = Vec(contexts, Flipped(Decoupled(new ExecuteMicroOpWithData(nFUs))))
    val out = Decoupled(new ExecuteMicroOpWithData(nFUs))
    val available = Output(Vec(contexts, Bool()))
    val contended = Output(Bool())
    val priorityIssue = Output(Bool())
  })
  val occupied = RegInit(0.U(maxDepth.W))
  val allocation = Mux(io.out.fire && !io.out.bits.iterative,
    UIntToOH(io.out.bits.pipe_depth, maxDepth), 0.U(maxDepth.W))
  occupied := (occupied | allocation) >> 1
  for (i <- 0 until contexts) {
    val depth = io.in(i).bits.pipe_depth
    io.available(i) := io.in(i).bits.iterative ||
      (depth < maxDepth.U && !occupied(depth(depthBits - 1, 0)))
  }
  val eligible = io.in.zip(io.available).map { case (in, available) => in.valid && available }
  val indexBits = log2Ceil(contexts) max 1
  val selected = Wire(UInt(indexBits.W))
  val priorityIssue = WireInit(false.B)
  if (prioritizeCritical && contexts > 1) {
    val critical = io.in.map { in =>
      val op = in.bits
      val e0 = !op.acc && op.head && op.tail && op.eidx === 0.U && op.vd_eew === 2.U &&
        op.eidx_mask === 15.U && !op.use_wmask
      op.isOpf && ((op.acc && (op.acc_merge || op.acc_fold)) || e0)
    }
    // Separate index arbiters preserve fairness within each class without
    // duplicating the wide operand/data multiplexer.
    val urgent = Module(new RRArbiter(UInt(indexBits.W), contexts))
    val regular = Module(new RRArbiter(UInt(indexBits.W), contexts))
    for (i <- 0 until contexts) {
      urgent.io.in(i).valid := eligible(i) && critical(i)
      regular.io.in(i).valid := eligible(i) && !critical(i)
      urgent.io.in(i).bits := i.U
      regular.io.in(i).bits := i.U
    }
    val streak = RegInit(0.U(2.W))
    val chooseUrgent = urgent.io.out.valid && (!regular.io.out.valid || streak < 3.U)
    io.out.valid := Mux(chooseUrgent, urgent.io.out.valid, regular.io.out.valid)
    selected := Mux(chooseUrgent, urgent.io.out.bits, regular.io.out.bits)
    urgent.io.out.ready := io.out.ready && chooseUrgent
    regular.io.out.ready := io.out.ready && !chooseUrgent
    when(!regular.io.out.valid) { streak := 0.U }
    when(io.out.fire) {
      streak := Mux(chooseUrgent && regular.io.out.valid, streak + 1.U, 0.U)
    }
    priorityIssue := io.out.fire && chooseUrgent
  } else {
    val arb = Module(new RRArbiter(UInt(indexBits.W), contexts))
    for (i <- 0 until contexts) {
      arb.io.in(i).valid := eligible(i)
      arb.io.in(i).bits := i.U
    }
    io.out.valid := arb.io.out.valid
    selected := arb.io.out.bits
    arb.io.out.ready := io.out.ready
  }
  val selectedOH = UIntToOH(selected, contexts)
  io.out.bits := Mux1H(selectedOH, io.in.map(_.bits))
  for (i <- 0 until contexts) {
    io.in(i).ready := io.out.ready && io.out.valid && selectedOH(i)
  }
  io.contended := io.out.fire && PopCount(VecInit(eligible)) > 1.U
  io.priorityIssue := priorityIssue
  when(io.out.fire && !io.out.bits.iterative) {
    assert(io.out.bits.pipe_depth < maxDepth.U &&
      !occupied(io.out.bits.pipe_depth(depthBits - 1, 0)), "shared FU result channel was double-booked")
  }
}
