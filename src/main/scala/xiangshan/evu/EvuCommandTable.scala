package xiangshan.evu

import chisel3._
import chisel3.experimental.dataview._
import chisel3.util._

class EvuCommandMetadata extends Bundle {
  val opcode = UInt(8.W)
  val vd = UInt(5.W)
  val epoch = UInt(2.W)
}

class EvuCommandIdentity extends EvuCommandMetadata {
  val pc = UInt(64.W)
}

class EvuCommandOutcome extends Bundle {
  val tval = UInt(64.W)
  val tvalValid = Bool()
  val exception = Bool()
  val fflags = UInt(5.W)
  val vxsat = Bool()
  val dirtyVs = Bool()
  val cause = UInt(4.W)
}

object EvuCommandOutcome {
  def from(completion: EvuCompletion): EvuCommandOutcome = {
    val outcome = Wire(new EvuCommandOutcome)
    outcome.tval := completion.tval
    outcome.tvalValid := completion.tvalValid
    outcome.exception := completion.exception
    outcome.fflags := completion.fflags
    outcome.vxsat := completion.vxsat
    outcome.dirtyVs := completion.dirtyVs
    outcome.cause := completion.cause
    outcome
  }
}

class EvuCommandResult(p: EvuParams) extends Bundle {
  val jobId = UInt(log2Ceil(p.commandDepth).W)
  val epoch = UInt(2.W)
  val outcome = new EvuCommandOutcome
}

class EvuCommandRecord extends Bundle {
  val identity = new EvuCommandMetadata
  val outcome = new EvuCommandOutcome
}

/** One ordered command window; its row number is also the execution age tag. */
class EvuCommandTable(p: EvuParams, cpuOwnedPc: Boolean = false) extends Module {
  private val depth = p.commandDepth
  private val idBits = log2Ceil(depth)
  val io = IO(new Bundle {
    val allocate = Flipped(Decoupled(new EvuCommandIdentity))
    val nextId = Output(UInt(idBits.W))
    val result = Flipped(Decoupled(new EvuCommandResult(p)))
    val completion = Decoupled(new EvuClientResult(p, cpuOwnedPc))
    val scheduleRelease = Input(UInt(depth.W))
    val scheduleHead = Output(UInt(idBits.W))
    val flush = Input(Bool())
    val live = Output(Vec(depth, Bool()))
    val completed = Output(Vec(depth, Bool()))
    val state = Output(Vec(depth, UInt(3.W)))
    val head = Output(UInt(idBits.W))
    val count = Output(UInt(log2Ceil(depth + 1).W))
    val busy = Output(Bool())
    val traceId = Option.when(p.enableDifftest)(Input(UInt(idBits.W)))
    val tracePc = Option.when(p.enableDifftest)(Output(UInt(64.W)))
  })

  val records = Reg(Vec(depth, new EvuCommandRecord))
  // Only the standalone completion or a simulation trace needs a PC indexed by job.
  val pcs = Option.when(!cpuOwnedPc || p.enableDifftest)(Reg(Vec(depth, UInt(64.W))))
  val live = RegInit(VecInit.fill(depth)(false.B))
  val completed = RegInit(VecInit.fill(depth)(false.B))
  val scheduleActive = RegInit(VecInit.fill(depth)(false.B))
  val state = RegInit(VecInit.fill(depth)(EvuJobState.Free))
  val head = RegInit(0.U(idBits.W))
  val tail = RegInit(0.U(idBits.W))
  val count = RegInit(0.U(log2Ceil(depth + 1).W))

  val retiring = io.completion.fire
  val replacing = retiring && head === tail
  io.allocate.ready := !io.flush && (!live(tail) || replacing)
  io.nextId := tail
  io.completion.valid := count =/= 0.U && completed(head) && !io.flush
  val record = records(head)
  io.completion.bits.pc.foreach(_ := pcs.get(head))
  io.completion.bits.opcode := record.identity.opcode
  io.completion.bits.vd := record.identity.vd
  io.completion.bits.epoch := record.identity.epoch
  io.completion.bits.jobId := head
  io.completion.bits.tval := record.outcome.tval
  io.completion.bits.tvalValid := record.outcome.tvalValid
  io.completion.bits.exception := record.outcome.exception
  io.completion.bits.fflags := record.outcome.fflags
  io.completion.bits.vxsat := record.outcome.vxsat
  io.completion.bits.dirtyVs := record.outcome.dirtyVs
  io.completion.bits.cause := record.outcome.cause

  val resultId = io.result.bits.jobId
  val matches = live(resultId) && records(resultId).identity.epoch === io.result.bits.epoch
  // Stale responses can drain, but cannot overwrite a new owner or complete twice.
  io.result.ready := !io.flush && (!matches || !completed(resultId))
  when(io.result.fire && matches) {
    records(resultId).outcome := io.result.bits.outcome
    records(resultId).outcome.dirtyVs := io.result.bits.outcome.dirtyVs &&
      records(resultId).identity.opcode =/= EvuOpcode.Store
    completed(resultId) := true.B
    scheduleActive(resultId) := false.B
  }
  for (i <- 0 until depth) {
    when(io.scheduleRelease(i)) { scheduleActive(i) := false.B }
    when(live(i) && state(i) === EvuJobState.Accepted) { state(i) := EvuJobState.Running }
  }
  when(retiring) {
    live(head) := false.B
    completed(head) := false.B
    state(head) := Mux(record.outcome.exception, EvuJobState.Exception, EvuJobState.Completed)
    head := head + 1.U
  }
  // Allocation wins when the oldest full-window row is retired and reused together.
  when(io.allocate.fire) {
    records(tail).identity := io.allocate.bits.viewAsSupertype(new EvuCommandMetadata)
    pcs.foreach(_(tail) := io.allocate.bits.pc)
    live(tail) := true.B
    completed(tail) := false.B
    scheduleActive(tail) := true.B
    state(tail) := EvuJobState.Accepted
    tail := tail + 1.U
  }
  when(io.allocate.fire =/= retiring) {
    count := Mux(io.allocate.fire, count + 1.U, count - 1.U)
  }

  // CPU reporting must not hold the internal oldest-reader priority behind a
  // store whose data has left the VRF but whose physical Ack is still pending.
  val active = scheduleActive.asUInt
  val beforeWrap = active & (~0.U(depth.W) << tail)
  io.scheduleHead := Mux(active.orR,
    Mux(beforeWrap.orR, PriorityEncoder(beforeWrap), PriorityEncoder(active)), tail)
  io.live := live
  io.completed := completed
  io.state := state
  io.head := head
  io.count := count
  io.busy := count =/= 0.U
  io.tracePc.foreach(_ := pcs.get(io.traceId.get))

  when(io.flush) {
    live.foreach(_ := false.B)
    completed.foreach(_ := false.B)
    scheduleActive.foreach(_ := false.B)
    state.foreach(_ := EvuJobState.Flushed)
    head := 0.U
    tail := 0.U
    count := 0.U
  }
  assert(count === PopCount(live), "EVU unified command occupancy mismatch")
  assert(!(completed.asUInt & ~live.asUInt).orR, "EVU completed row has no live owner")
}
