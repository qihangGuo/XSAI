package xiangshan.evu

import chisel3._
import chisel3.util._

/** Cross-path reservations live until completion acceptance, not execution
  * issue. Memory has a bounded command window; independent compute need not drain it.
  */
class EvuCommandScoreboard(p: EvuParams) extends Module {
  private val jobBits = log2Ceil(p.commandDepth)
  val io = IO(new Bundle {
    val commandValid = Input(Bool())
    val readMask = Input(UInt(p.vectorRegs.W))
    val writeMask = Input(UInt(p.vectorRegs.W))
    val matrixReadMask = Input(UInt(p.vectorRegs.W))
    val isMemory = Input(Bool())
    val chainable = Input(Bool())
    val jobId = Input(UInt(jobBits.W))
    val accept = Input(Bool())
    val release = Input(Valid(UInt(jobBits.W)))
    val flush = Input(Bool())
    val allowed = Output(Bool())
    val busy = Output(Bool())
    val busyWriteMask = Output(UInt(p.vectorRegs.W))
    val memoryOrderStalls = Output(UInt(64.W))
    val registerHazardStalls = Output(UInt(64.W))
    val memoryComputeOverlap = Output(UInt(64.W))
    val memoryOutstanding = Output(UInt(log2Ceil(p.memoryCommandDepth + 1).W))
  })
  private val valid = RegInit(VecInit.fill(p.commandDepth)(false.B))
  private val reads = Reg(Vec(p.commandDepth, UInt(p.vectorRegs.W)))
  private val writes = Reg(Vec(p.commandDepth, UInt(p.vectorRegs.W)))
  private val chainable = Reg(Vec(p.commandDepth, Bool()))
  private val memoryJobs = RegInit(VecInit.fill(p.commandDepth)(false.B))
  val memoryCount = PopCount(memoryJobs)
  val memoryLive = memoryCount =/= 0.U
  io.memoryOutstanding := memoryCount
  val busyReads = valid.zip(reads).map { case (v, m) => Mux(v, m, 0.U) }.reduce(_ | _)
  val busyWrites = valid.zip(writes).map { case (v, m) => Mux(v, m, 0.U) }.reduce(_ | _)
  val ordered = !io.isMemory || memoryCount < p.memoryCommandDepth.U
  val protectedJobs = valid.zip(chainable).map { case (v, c) =>
    v && !(p.egChaining.B && io.chainable && c)
  }
  val protectedReads = protectedJobs.zip(reads).map { case (v, m) => Mux(v, m, 0.U) }.reduce(_ | _)
  val protectedWrites = protectedJobs.zip(writes).map { case (v, m) => Mux(v, m, 0.U) }.reduce(_ | _)
  val independent = !(io.readMask & protectedWrites).orR &&
    !(io.writeMask & (protectedReads | protectedWrites | io.matrixReadMask)).orR
  io.allowed := ordered && independent && !io.flush
  io.busy := valid.asUInt.orR
  io.busyWriteMask := busyWrites
  val orderStalls = RegInit(0.U(64.W))
  val registerStalls = RegInit(0.U(64.W))
  val overlap = RegInit(0.U(64.W))
  when(io.commandValid && !ordered) { orderStalls := orderStalls + 1.U }
  when(io.commandValid && ordered && !independent) { registerStalls := registerStalls + 1.U }
  when(memoryLive && valid.zip(memoryJobs).map { case (v, m) => v && !m }.reduce(_ || _)) {
    overlap := overlap + 1.U
  }
  when(io.release.valid) {
    valid(io.release.bits) := false.B
    memoryJobs(io.release.bits) := false.B
  }
  when(io.accept) {
    assert(io.allowed, "EVU accepted a command across a reservation or memory-order hazard")
    assert(!valid(io.jobId), "EVU reused a live command ID")
    valid(io.jobId) := true.B
    reads(io.jobId) := io.readMask
    writes(io.jobId) := io.writeMask
    chainable(io.jobId) := io.chainable
    memoryJobs(io.jobId) := io.isMemory
  }
  when(io.flush) {
    valid.foreach(_ := false.B)
    memoryJobs.foreach(_ := false.B)
    orderStalls := 0.U
    registerStalls := 0.U
    overlap := 0.U
  }
  io.memoryOrderStalls := orderStalls
  io.registerHazardStalls := registerStalls
  io.memoryComputeOverlap := overlap
}
