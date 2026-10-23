package xiangshan.evu

import chisel3._
import chisel3.util._
import xscache.coupledL2.{MatrixField, MatrixKey}
import freechips.rocketchip.diplomacy.{IdRange, LazyModule, LazyModuleImp, ValName}
import freechips.rocketchip.tile.TileVisibilityNodeKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters

class EvuTopIO(p: EvuParams) extends Bundle {
  val drain = Flipped(new EvuDrainIO)
  val command    = Flipped(Decoupled(new EvuCommand(p)))
  val dispatch   = Flipped(Decoupled(new EvuDispatchRequest(p)))
  val completion = Decoupled(new EvuCompletion(p))
  val flush      = Input(Bool())
  val busy       = Output(Bool())
  val matrixInput = Flipped(Decoupled(new MatrixMicroBlock(p)))
  val matrixOutput = Decoupled(UInt(p.dlenBits.W))
  val cuteWrite = Decoupled(new EvuCuteWrite(p))
  val jobStatus  = Output(Vec(p.commandDepth, UInt(3.W)))
  val jobEpoch   = Output(UInt(2.W))
  val matrixScaleTag = Output(UInt(p.regIndexWidth.W))
  val matrixBiasTag = Output(UInt(p.regIndexWidth.W))
  val perf = Output(new EvuPerf)
  val storeTrace = Output(Valid(new EvuStoreTrace))
  val matrixTrace = Output(Valid(new EvuMatrixTrace(p)))
  val archSnapshot = Option.when(p.enableDifftest)(Output(Valid(new EvuArchSnapshot(p))))
}

class EvuMatrixTrace(p: EvuParams) extends Bundle {
  val row = UInt(32.W)
  val col = UInt(32.W)
  val data = UInt(p.dlenBits.W)
  val mask = UInt(64.W)
  val fflags = UInt(5.W)
  val last = Bool()
}

class EvuArchSnapshot(p: EvuParams) extends Bundle {
  val vr = Vec(p.vectorRegs, Vec(p.vlenBits / 64, UInt(64.W)))
  // Existing EVU-local state: VL, VTYPE, VSTART, VLENB, configuration-valid.
  val csr = Vec(5, UInt(64.W))
}

class EvuStoreTrace extends Bundle {
  val addr = UInt(64.W)
  val data = UInt(512.W)
  val mask = UInt(64.W)
}

/** Low-cost counters for server-side RTL/performance validation. */
class EvuPerf extends Bundle {
  val cycles       = UInt(64.W)
  val busyCycles   = UInt(64.W)
  val stallCycles  = UInt(64.W)
  val memRequests  = UInt(64.W)
  val memResponses = UInt(64.W)
  val completions  = UInt(64.W)
  val chained      = UInt(64.W)
  val matrixBeats  = UInt(64.W)
}

/** Architectural job states exported to the scalar/AMU side. */
object EvuJobState {
  val Free = 0.U(3.W)
  val Accepted = 1.U(3.W)
  val Running = 2.U(3.W)
  val Completed = 3.U(3.W)
  val Exception = 4.U(3.W)
  val Flushed = 5.U(3.W)
}

/** External vector client. Its node is attached to the tile's private HBL2 xbar. */
class EvuTop(val evuParams: EvuParams)(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(Seq(TLMasterPortParameters.v1(
    Seq(TLMasterParameters.v1("XSAIExternalVector", IdRange(0, evuParams.maxMemOutstanding))),
    requestFields = Seq(MatrixField(2))
  )))

  // CoreModule-based Saturn blocks need the negotiated TileLink bundle in
  // TileVisibilityNodeKey for address-width sizing. Keep the EVU client
  // behind an ephemeral identity node so the same negotiated edge is also
  // available to the Saturn parameter context.
  val visibilityNode = TLEphemeralNode()(ValName("evu_visibility"))
  visibilityNode := TLWidthWidget(64) := node

  lazy val module = new EvuTopImp(this)
}

class EvuTopImp(outer: EvuTop)(implicit p: Parameters) extends LazyModuleImp(outer) {
  private val ep              = outer.evuParams
  val io                      = IO(new EvuTopIO(ep))
  private val memoryBusy = Wire(Bool())
  private val control         = Module(new EvuControlSequencer(ep))
  private val vectorParameters = p.alterMap(Map(
    TileVisibilityNodeKey -> outer.visibilityNode
  ))
  private val vectorBackend   = Module(new EvuVectorBackend(ep)(vectorParameters))
  private val dispatchAdapter = Module(new EvuDispatchAdapter(ep))
  private val commandArbiter  = Module(new Arbiter(new EvuCommand(ep), 2))
  private val jobValid         = RegInit(VecInit.fill(ep.commandDepth)(false.B))
  private val jobState         = RegInit(VecInit.fill(ep.commandDepth)(0.U(3.W)))
  private val jobEpochs        = RegInit(VecInit.fill(ep.commandDepth)(0.U(2.W)))
  private val nextJobId        = RegInit(0.U(log2Ceil(ep.commandDepth).W))
  private val currentEpoch     = RegInit(0.U(2.W))
  private val activePageMap = RegInit(0.U.asTypeOf(new EvuPageMap(ep)))
  // EVU-local RVV state.  It is architectural state of the single Saturn
  // vector unit, not a second vector register file.
  private val rvvConfigured = RegInit(false.B)
  private val rvvVl = RegInit((ep.vlenBits / 32).U(log2Ceil(ep.vlenBits + 1).W))
  private val rvvSew = RegInit(2.U(3.W))
  private val rvvVta = RegInit(false.B)
  private val rvvVma = RegInit(false.B)
  private val rvvVstart = RegInit(0.U(log2Ceil(ep.vlenBits + 1).W))
  private val rvvVill = RegInit(false.B)
  private val rvvVlmulSign = RegInit(false.B)
  private val rvvVlmulMag = RegInit(0.U(2.W))
  // VLMAX is derived from the current vtype.  LMUL fractions are rounded
  // down just as a bounded hardware implementation must do; illegal zero
  // lengths are rejected by the command-ready path below.
  private val rvvBaseVlm = (ep.vlenBytes.U >> rvvSew)
  private val rvvVlm = Mux(rvvVlmulSign,
    rvvBaseVlm >> rvvVlmulMag,
    rvvBaseVlm << rvvVlmulMag)
  private val matrixJobActive = RegInit(false.B)
  private val matrixPC = RegInit(0.U(64.W))
  private val matrixErrorAddress = RegInit(0.U(64.W))
  private val matrixCause = RegInit(0.U(4.W))
  private val matrixBase = RegInit(0.U(64.W))
  private val matrixRowBytes = RegInit(0.U(32.W))
  private val matrixScaleReg = RegInit(0.U(ep.regIndexWidth.W))
  private val matrixBiasReg = RegInit(0.U(ep.regIndexWidth.W))
  private val matrixStickyError = RegInit(false.B)
  private val matrixStickyFflags = RegInit(0.U(5.W))
  private val matrixBeatActive = RegInit(false.B)
  private val matrixBeatWaiting = RegInit(false.B)
  private val matrixBeatHalf = RegInit(false.B)
  private val matrixBeatNeedHigh = RegInit(false.B)
  private val matrixBeatData = Reg(UInt(ep.dlenBits.W))
  private val matrixBeatAddr = Reg(UInt(64.W))
  private val matrixBeatLast = RegInit(false.B)
  private val (bus, edge)     = outer.node.out.head
  require(edge.bundle.dataBits == 512, "EVU must face HBL2 through TLWidthWidget(64)")
  require(ep.mlenBytes <= 64, "EVU logical requests must fit in a line packet")

  dispatchAdapter.io.in <> io.dispatch
  dispatchAdapter.io.flush := io.flush
  commandArbiter.io.in(0) <> io.command
  commandArbiter.io.in(1) <> dispatchAdapter.io.out
  val liveJobs = PopCount(jobValid)
  val hasJobSlot = !jobValid(nextJobId) && liveJobs < ep.commandDepth.U
  val launchCanStart = commandArbiter.io.out.bits.opcode =/= EvuOpcode.Launch || !matrixJobActive
  val issuedCommand = Wire(Decoupled(new EvuCommand(ep)))
  issuedCommand.valid := commandArbiter.io.out.valid && !io.flush && hasJobSlot && launchCanStart
  issuedCommand.bits := commandArbiter.io.out.bits
  issuedCommand.bits.jobId := nextJobId
  issuedCommand.bits.epoch := currentEpoch
  val stateControl = commandArbiter.io.out.bits.opcode === EvuOpcode.SetVli ||
    commandArbiter.io.out.bits.opcode === EvuOpcode.SetVl
  val stateVectorOp = !stateControl &&
    commandArbiter.io.out.bits.opcode =/= EvuOpcode.Launch &&
    commandArbiter.io.out.bits.opcode =/= EvuOpcode.Wait &&
    commandArbiter.io.out.bits.opcode =/= EvuOpcode.Fence &&
    commandArbiter.io.out.bits.opcode =/= EvuOpcode.SetCfg
  val snap = issuedCommand.bits.config
  val useSnapshot = snap.valid
  val configured = Mux(useSnapshot, snap.configured, rvvConfigured)
  val effectiveVl = Mux(useSnapshot, snap.vl, rvvVl)
  val effectiveType = snap.vtype
  val effectiveVill = Mux(useSnapshot, snap.configured && snap.vill, rvvVill)
  val effectiveLmul = effectiveType(2, 0)
  when (configured && stateVectorOp) {
    issuedCommand.bits.vl := effectiveVl
    issuedCommand.bits.sew := Mux(useSnapshot, effectiveType(5, 3), rvvSew)
    // Once vtype.vill is set, any following vector operation is an
    // architectural illegal instruction until a legal xvsetvli/xvsetvl
    // reconfigures the unit. Route it through the normal completion path so
    // software cannot deadlock on a command whose VL is zero.
    when (effectiveVill) { issuedCommand.bits.opcode := EvuOpcode.Invalid }
  }
  val saturnRoute = Seq(
    EvuOpcode.Add, EvuOpcode.Sub, EvuOpcode.And, EvuOpcode.Or,
    EvuOpcode.Xor, EvuOpcode.Mul, EvuOpcode.Mac,
    EvuOpcode.ReduceSum, EvuOpcode.ReduceMax, EvuOpcode.ReduceMin,
    EvuOpcode.Sll, EvuOpcode.Srl, EvuOpcode.Sra, EvuOpcode.Min, EvuOpcode.Max,
    EvuOpcode.MinU, EvuOpcode.MaxU, EvuOpcode.FAdd, EvuOpcode.FSub,
    EvuOpcode.FMul, EvuOpcode.FMacc, EvuOpcode.FDiv, EvuOpcode.FMin,
    EvuOpcode.FMax, EvuOpcode.FReduceSum, EvuOpcode.FReduceMin, EvuOpcode.FReduceMax,
    EvuOpcode.WAdd, EvuOpcode.WSub, EvuOpcode.WMul, EvuOpcode.SatAdd, EvuOpcode.SatSub,
    EvuOpcode.SlideUp, EvuOpcode.SlideDown, EvuOpcode.Gather, EvuOpcode.ReduceAnd, EvuOpcode.ReduceOr,
    EvuOpcode.ReduceXor, EvuOpcode.Load, EvuOpcode.Store,
    EvuOpcode.Exp2, EvuOpcode.QuantToCute, EvuOpcode.SetCfg,
    EvuOpcode.Scatter, EvuOpcode.Invalid
  ).map(issuedCommand.bits.opcode === _).reduce(_ || _)
  val controlRoute = Seq(EvuOpcode.Launch, EvuOpcode.Wait, EvuOpcode.Fence,
    EvuOpcode.SetVli, EvuOpcode.SetVl).map(issuedCommand.bits.opcode === _).reduce(_ || _)
  control.io.command.valid := issuedCommand.valid && controlRoute
  control.io.command.bits := issuedCommand.bits
  vectorBackend.io.vta := Mux(useSnapshot, configured && effectiveType(6), rvvVta)
  vectorBackend.io.vma := Mux(useSnapshot, configured && effectiveType(7), rvvVma)
  vectorBackend.io.vlmulSign := Mux(useSnapshot, configured && effectiveLmul(2), rvvVlmulSign)
  vectorBackend.io.vlmulMag := Mux(useSnapshot, Mux(!configured, 0.U, Mux(effectiveLmul(2), (8.U - effectiveLmul)(1, 0), effectiveLmul(1, 0))), rvvVlmulMag)
  vectorBackend.io.vstart := Mux(useSnapshot, 0.U, rvvVstart)
  vectorBackend.io.vill := effectiveVill
  vectorBackend.io.command.valid := issuedCommand.valid && saturnRoute
  vectorBackend.io.command.bits := issuedCommand.bits
  vectorBackend.io.flush := io.flush
  issuedCommand.ready := Mux(saturnRoute, vectorBackend.io.command.ready,
    Mux(controlRoute, control.io.command.ready, false.B))
  // Do not wrap a job ID while all command-table entries are still live.
  // Backend readiness alone is insufficient because a long Saturn
  // operation can hold a completion for many cycles.
  commandArbiter.io.out.ready := issuedCommand.ready && !io.flush &&
    hasJobSlot && launchCanStart
  val completionArbiter = Module(new Arbiter(new EvuCompletion(ep), 2))
  completionArbiter.io.in(0) <> control.io.completion
  when(control.io.completion.bits.exception) {
    completionArbiter.io.in(0).bits.pc := matrixPC
    completionArbiter.io.in(0).bits.tval := matrixErrorAddress
    completionArbiter.io.in(0).bits.tvalValid := true.B
    completionArbiter.io.in(0).bits.cause := matrixCause
  }
  when(control.io.completion.fire && control.io.completion.bits.opcode === EvuOpcode.Fence) {
    matrixStickyFflags := 0.U
    matrixStickyError := false.B
  }
  completionArbiter.io.in(1) <> vectorBackend.io.completion
  val completionQueue = Queue(completionArbiter.io.out, ep.completionDepth, flush = Some(io.flush))

  // Backend completion may be out of order (Saturn VAT release, VMU,
  // and fused control paths have different latencies). Keep a small
  // job-indexed completion table and retire strictly in accepted order.
  private val jobIdWidth = log2Ceil(ep.commandDepth)
  val acceptedOrder = Reg(Vec(ep.commandDepth, UInt(jobIdWidth.W)))
  val acceptedHead = RegInit(0.U(jobIdWidth.W))
  val acceptedTail = RegInit(0.U(jobIdWidth.W))
  val acceptedCount = RegInit(0.U(log2Ceil(ep.commandDepth + 1).W))
  val completionByJob = RegInit(VecInit.fill(ep.commandDepth)(0.U.asTypeOf(new EvuCompletion(ep))))
  val completionByJobValid = RegInit(VecInit.fill(ep.commandDepth)(false.B))
  val completionId = completionQueue.bits.jobId
  val expectedCompletion = jobValid(completionId) && jobEpochs(completionId) === completionQueue.bits.epoch
  completionQueue.ready := !io.flush && !completionByJobValid(completionId)
  when(completionQueue.fire && expectedCompletion) {
    completionByJob(completionId) := completionQueue.bits
    completionByJobValid(completionId) := true.B
  }
  val retireId = Mux(acceptedCount === 0.U, 0.U, acceptedOrder(acceptedHead))
  io.completion.valid := acceptedCount =/= 0.U && completionByJobValid(retireId) && !io.flush
  io.completion.bits := completionByJob(retireId)
  when(io.completion.fire) {
    completionByJobValid(retireId) := false.B
    acceptedHead := acceptedHead + 1.U
    // A successful vector instruction retires all elements.  On an
    // exception, retain vstart so software can restart from the faulting
    // element.
    when(!io.completion.bits.exception) { rvvVstart := 0.U }
  }
  when(issuedCommand.fire) {
    when(issuedCommand.bits.opcode === EvuOpcode.Load || issuedCommand.bits.opcode === EvuOpcode.Store ||
      issuedCommand.bits.opcode === EvuOpcode.Launch) {
      activePageMap := issuedCommand.bits.pageMap
    }
    acceptedOrder(acceptedTail) := nextJobId
    acceptedTail := acceptedTail + 1.U
  }
  when(!io.flush) {
    when(issuedCommand.fire && !io.completion.fire) { acceptedCount := acceptedCount + 1.U }
    when(io.completion.fire && !issuedCommand.fire) { acceptedCount := acceptedCount - 1.U }
  }
  control.io.flush := io.flush
  control.io.jobValid := jobValid
  control.io.jobState := jobState
  control.io.matrixBusy := matrixJobActive || matrixBeatActive || matrixBeatWaiting || vectorBackend.io.busy || memoryBusy
  control.io.matrixException := matrixStickyError
  control.io.matrixFflags := matrixStickyFflags
  io.cuteWrite <> vectorBackend.io.cuteWrite

  val matrixBeatCanAccept = matrixJobActive && !matrixBeatActive
  val matrixSpecialValid = WireDefault(false.B)
  val matrixSpecialReady = WireDefault(false.B)
  val matrixSpecialFflags = WireDefault(0.U(5.W))
  val matrixSpecialBits = Wire(new MatrixMicroBlock(ep))
  matrixSpecialBits := 0.U.asTypeOf(matrixSpecialBits)
  // A result carries the metadata of the accepted input beat.  The writer
  // must not look back at io.matrixInput, which may already contain the next
  // CUTE beat when the current result is ready.
  val matrixMeta = Wire(new MatrixMicroBlock(ep))
  matrixMeta := matrixSpecialBits
  val matrixOutputNBlock = matrixMeta.tileId(11, 4)
  val matrixOutputMBlock = matrixMeta.tileId(3, 0)
  vectorBackend.io.matrixInput.valid := io.matrixInput.valid && matrixJobActive
  vectorBackend.io.matrixInput.bits := io.matrixInput.bits
  matrixSpecialReady := vectorBackend.io.matrixInput.ready
  vectorBackend.io.matrixOutput.ready := matrixBeatCanAccept && io.matrixOutput.ready
  matrixSpecialValid := vectorBackend.io.matrixOutput.valid
  matrixSpecialBits := vectorBackend.io.matrixOutput.bits
  matrixSpecialFflags := vectorBackend.io.matrixFflags
  io.matrixInput.ready := matrixSpecialReady && matrixJobActive
  io.matrixOutput.valid := matrixSpecialValid && matrixBeatCanAccept
  io.matrixOutput.bits := matrixSpecialBits.data

  val matrixTileDim = Mux(matrixMeta.tileDim === 0.U, 8.U, matrixMeta.tileDim)
  val matrixRow = Mux(matrixMeta.tileDim === 0.U, matrixMeta.microblockId, matrixMeta.row)
  val matrixCol = Mux(matrixMeta.tileDim === 0.U, 0.U, matrixMeta.col)
  val matrixGlobalN = matrixOutputNBlock * matrixTileDim + matrixRow
  val matrixGlobalM = matrixOutputMBlock * matrixTileDim + matrixCol
  val matrixByteOffset = matrixGlobalN * matrixRowBytes + matrixGlobalM * 4.U
  val matrixResultFire = io.matrixOutput.valid && io.matrixOutput.ready
  val matrixResultFflags = matrixSpecialFflags
  io.matrixTrace.valid := matrixResultFire && !io.flush
  io.matrixTrace.bits.row := matrixGlobalN
  io.matrixTrace.bits.col := matrixGlobalM
  io.matrixTrace.bits.data := io.matrixOutput.bits
  io.matrixTrace.bits.mask := matrixMeta.validLanes
  io.matrixTrace.bits.fflags := matrixResultFflags
  io.matrixTrace.bits.last := matrixMeta.taskLast
  when(matrixResultFire) {
    matrixBeatActive := true.B
    matrixBeatHalf := false.B
    matrixBeatNeedHigh := false.B
    matrixBeatData := matrixSpecialBits.data
    matrixBeatAddr := matrixBase + matrixByteOffset
    matrixBeatNeedHigh := PopCount(matrixMeta.validLanes) > (ep.mlenBits / 32).U
    matrixBeatLast := matrixMeta.taskLast
    matrixStickyFflags := matrixStickyFflags | matrixResultFflags
    when(matrixStickyError) {
      matrixBeatActive := false.B
      when(matrixMeta.taskLast) { matrixJobActive := false.B }
    }
  }
  when(issuedCommand.fire) {
    when (stateControl && !useSnapshot) {
      rvvConfigured := true.B
      // The low eight bits of aux use the architectural vtype layout:
      // vlmul[2:0], vsew[5:3], vta[6], vma[7].
      val newVlmulSign = issuedCommand.bits.aux(2)
      val newVlmulMag = issuedCommand.bits.aux(1, 0)
      val (newVl, effectiveSew, illegalVtype) = EvuVtype.configure(ep,
        issuedCommand.bits.opcode === EvuOpcode.SetVli, issuedCommand.bits.sew,
        issuedCommand.bits.vl, issuedCommand.bits.aux)
      rvvVill := illegalVtype
      rvvVl := newVl
      rvvSew := effectiveSew
      rvvVta := issuedCommand.bits.aux(6)
      rvvVma := issuedCommand.bits.aux(7)
      rvvVlmulSign := newVlmulSign
      rvvVlmulMag := newVlmulMag
      rvvVstart := 0.U
    }
    jobValid(nextJobId) := true.B
    jobState(nextJobId) := EvuJobState.Accepted
    jobEpochs(nextJobId) := currentEpoch
    nextJobId := nextJobId + 1.U
    when(issuedCommand.bits.opcode === EvuOpcode.Launch) {
      matrixJobActive := true.B
      matrixBase := issuedCommand.bits.base
      matrixPC := issuedCommand.bits.pc
      matrixRowBytes := Mux(issuedCommand.bits.length === 0.U, ep.vlenBytes.U, issuedCommand.bits.length)
      matrixScaleReg := issuedCommand.bits.vs2
      matrixBiasReg := issuedCommand.bits.vd
      matrixStickyError := false.B
      matrixStickyFflags := 0.U
    }
  }
  for (i <- 0 until ep.commandDepth) {
    when(jobValid(i) && jobState(i) === EvuJobState.Accepted && !io.flush) {
      jobState(i) := EvuJobState.Running
    }
  }
  io.jobStatus := jobState
  io.jobEpoch := currentEpoch
  io.matrixScaleTag := matrixScaleReg
  io.matrixBiasTag := matrixBiasReg
  when(io.completion.fire && !io.flush && jobValid(io.completion.bits.jobId) &&
    jobEpochs(io.completion.bits.jobId) === io.completion.bits.epoch) {
    jobState(io.completion.bits.jobId) := Mux(io.completion.bits.exception,
      EvuJobState.Exception, EvuJobState.Completed)
    jobValid(io.completion.bits.jobId) := false.B
  }
  when(io.flush) {
    for (i <- 0 until ep.commandDepth) {
      jobValid(i) := false.B
      jobState(i) := EvuJobState.Flushed
      completionByJobValid(i) := false.B
    }
    acceptedHead := 0.U
    acceptedTail := 0.U
    acceptedCount := 0.U
    nextJobId := 0.U
    currentEpoch := currentEpoch + 1.U
    rvvConfigured := false.B
    rvvVill := false.B
    rvvVstart := 0.U
    matrixJobActive := false.B
    matrixBeatActive := false.B
    matrixBeatWaiting := false.B
    matrixBeatHalf := false.B
    matrixBeatNeedHigh := false.B
    matrixStickyError := false.B
    matrixStickyFflags := 0.U
    activePageMap := 0.U.asTypeOf(activePageMap)
  }

  bus.b.ready := false.B
  bus.c.valid := false.B
  bus.c.bits  := DontCare
  bus.e.valid := false.B
  bus.e.bits  := DontCare
  bus.a.valid := false.B
  bus.a.bits  := DontCare
  bus.d.ready := false.B

  val matrixMemReq = Wire(Decoupled(new EvuMemoryRequest(ep)))
  val matrixMemResp = Wire(Decoupled(new EvuMemoryResponse(ep)))
  matrixMemReq.valid := matrixBeatActive && !matrixBeatWaiting && !io.flush && (!matrixBeatHalf || matrixBeatNeedHigh)
  matrixMemReq.bits.addr := matrixBeatAddr + Mux(matrixBeatHalf, ep.mlenBytes.U, 0.U)
  if (ep.mlenBits == ep.dlenBits) {
    matrixMemReq.bits.data := matrixBeatData
  } else {
    matrixMemReq.bits.data := Mux(matrixBeatHalf,
      matrixBeatData(ep.dlenBits - 1, ep.mlenBits),
      matrixBeatData(ep.mlenBits - 1, 0))
  }
  matrixMemReq.bits.mask := ((BigInt(1) << ep.mlenBytes) - 1).U
  matrixMemReq.bits.write := true.B
  matrixMemReq.bits.tag := (ep.maxMemOutstanding - 1).U
  matrixMemResp.ready := matrixBeatWaiting && !io.flush
  when(matrixMemReq.fire) { matrixBeatWaiting := true.B }
  when(matrixMemResp.fire) {
    matrixBeatWaiting := false.B
    when(matrixMemResp.bits.error) {
      when(!matrixStickyError) {
        matrixErrorAddress := matrixBeatAddr + Mux(matrixBeatHalf, ep.mlenBytes.U, 0.U)
        matrixCause := matrixMemResp.bits.fault
      }
      matrixStickyError := true.B
      matrixBeatActive := false.B
      when(matrixBeatLast) { matrixJobActive := false.B }
    }.elsewhen(!matrixBeatHalf && matrixBeatNeedHigh) {
      matrixBeatHalf := true.B
    }.otherwise {
      matrixBeatActive := false.B
      matrixBeatHalf := false.B
      when(matrixBeatLast) { matrixJobActive := false.B }
    }
  }

  // Matrix-output and VMU ports keep MLEN; only the HBL2 boundary is 64 bytes.
  val memoryArbiter = Module(new Arbiter(new EvuLineMemoryRequest(ep), 2))
  def connectMemoryRequest(index: Int, request: DecoupledIO[EvuMemoryRequest]): Unit = {
    val target = memoryArbiter.io.in(index)
    target.valid := request.valid
    request.ready := target.ready
    target.bits.addr := request.bits.addr
    target.bits.data := request.bits.data
    target.bits.mask := request.bits.mask
    target.bits.bytes := ep.mlenBytes.U
    target.bits.write := request.bits.write
    target.bits.tag := request.bits.tag
  }
  connectMemoryRequest(0, matrixMemReq)
  connectMemoryRequest(1, vectorBackend.io.memory.req)
  val memReq = memoryArbiter.io.out
  val lineAdapter = Module(new EvuHbl2LineAdapter(ep))
  lineAdapter.io.flush := io.flush
  // 0 = CUTE matrix output, 1..N-1 = Saturn.
  val globalTag = Mux(memoryArbiter.io.chosen === 1.U, memReq.bits.tag,
    (ep.maxMemOutstanding - 1).U)
  lineAdapter.io.client.req.valid := memReq.valid
  lineAdapter.io.client.req.bits := memReq.bits
  lineAdapter.io.client.req.bits.tag := globalTag
  memReq.ready := lineAdapter.io.client.req.ready
  val requestOwner = Reg(Vec(ep.maxMemOutstanding, Bool()))
  val requestClientTag = Reg(Vec(ep.maxMemOutstanding, UInt(ep.memoryTagWidth.W)))
  when(memReq.fire) {
    requestOwner(globalTag) := memoryArbiter.io.chosen
    requestClientTag(globalTag) := memReq.bits.tag
  }
  val memResp = lineAdapter.io.client.resp
  val responseOwner = requestOwner(memResp.bits.tag)
  def connectMemoryResponse(owner: Int, response: DecoupledIO[EvuMemoryResponse]): Unit = {
    response.valid := memResp.valid && responseOwner === owner.U
    response.bits.data := memResp.bits.data
    response.bits.error := memResp.bits.error
    response.bits.fault := memResp.bits.fault
    response.bits.tag := requestClientTag(memResp.bits.tag)
  }
  connectMemoryResponse(0, matrixMemResp)
  connectMemoryResponse(1, vectorBackend.io.memory.resp)
  memResp.ready := Mux(responseOwner, vectorBackend.io.memory.resp.ready,
    matrixMemResp.ready)

  val physicalReq = lineAdapter.io.memory.req
  val translation = Module(new EvuPageTranslate(ep))
  translation.io.pageMap := activePageMap
  // CUTE matrix output remains a privileged physical-address producer.
  translation.io.pageMap.enabled := activePageMap.enabled && requestOwner(physicalReq.bits.tag)
  translation.io.vaddr := physicalReq.bits.addr
  val physicalAddress = translation.io.paddr
  val (getLegal, getReq) = edge.Get(physicalReq.bits.tag, physicalAddress, 6.U)
  val (putLegal, putReq) = edge.Put(physicalReq.bits.tag, physicalAddress, 6.U,
    physicalReq.bits.data)
  val legal = translation.io.legal && edge.manager.containsSafe(physicalAddress) &&
    Mux(physicalReq.bits.write, putLegal, getLegal)
  // Keep reads on ordinary D. Matrix reads would instead use CUTE's sideband.
  getReq.user.lift(MatrixKey).foreach(_ := 0.U)
  putReq.user.lift(MatrixKey).foreach(_ := 1.U)
  bus.a.valid := physicalReq.valid && legal
  bus.a.bits := Mux(physicalReq.bits.write, putReq, getReq)
  // Unsupported addresses complete locally, including requests draining a flush.
  val accessErrors = Module(new Queue(new EvuLineMemoryResponse(ep), ep.maxMemOutstanding))
  accessErrors.io.enq.valid := physicalReq.valid && !legal
  accessErrors.io.enq.bits.data := 0.U
  accessErrors.io.enq.bits.error := true.B
  accessErrors.io.enq.bits.fault := EvuFault.Access
  accessErrors.io.enq.bits.tag := physicalReq.bits.tag
  physicalReq.ready := Mux(legal, bus.a.ready, accessErrors.io.enq.ready)
  val busResponses = Module(new Arbiter(new EvuLineMemoryResponse(ep), 2))
  busResponses.io.in(0) <> accessErrors.io.deq
  busResponses.io.in(1).valid := bus.d.valid
  busResponses.io.in(1).bits.data := bus.d.bits.data
  busResponses.io.in(1).bits.error := bus.d.bits.denied || bus.d.bits.corrupt
  busResponses.io.in(1).bits.fault := Mux(bus.d.bits.denied, EvuFault.Access,
    Mux(bus.d.bits.corrupt, EvuFault.Bus, EvuFault.None))
  busResponses.io.in(1).bits.tag := bus.d.bits.source
  bus.d.ready := busResponses.io.in(1).ready
  // Observe the actual physical PutFullData transactions, including each
  // half of a virtual access spanning noncontiguous physical pages.
  val physicalStores = Reg(Vec(ep.maxMemOutstanding, new EvuStoreTrace))
  val tracedStore = RegInit(VecInit.fill(ep.maxMemOutstanding)(false.B))
  when(bus.a.fire) {
    val tag = physicalReq.bits.tag
    physicalStores(tag).addr := physicalAddress
    physicalStores(tag).data := physicalReq.bits.data
    physicalStores(tag).mask := Fill(64, 1.U(1.W))
    tracedStore(tag) := physicalReq.bits.write
  }
  io.storeTrace.valid := bus.d.fire && tracedStore(bus.d.bits.source) &&
    !bus.d.bits.denied && !bus.d.bits.corrupt && !io.flush
  io.storeTrace.bits := physicalStores(bus.d.bits.source)
  when(bus.d.fire) { tracedStore(bus.d.bits.source) := false.B }
  when(io.flush) { tracedStore.foreach(_ := false.B) }
  lineAdapter.io.memory.resp <> busResponses.io.out
  memoryBusy := lineAdapter.io.busy
  when(bus.d.valid) {
    assert(bus.d.bits.source < ep.maxMemOutstanding.U, "EVU HBL2 response source out of range")
  }

  io.busy := control.io.busy || vectorBackend.io.busy ||
    dispatchAdapter.io.out.valid || io.dispatch.valid || lineAdapter.io.busy || jobValid.asUInt.orR ||
    matrixJobActive || matrixBeatActive || matrixBeatWaiting || io.matrixInput.valid ||
    completionQueue.valid
  val drainPending = RegInit(false.B)
  io.drain.request.ready := !drainPending && !io.flush
  when(io.drain.request.fire) { drainPending := true.B }
  io.drain.response.valid := drainPending && !io.busy && !io.flush
  io.drain.response.bits.fflags := matrixStickyFflags
  io.drain.response.bits.exception := matrixStickyError
  io.drain.response.bits.cause := matrixCause
  io.drain.response.bits.pc := matrixPC
  io.drain.response.bits.tval := matrixErrorAddress
  io.drain.response.bits.tvalValid := matrixStickyError
  when(io.drain.response.fire) {
    drainPending := false.B
    matrixStickyFflags := 0.U
    matrixStickyError := false.B
  }
  when(io.flush) { drainPending := false.B }
  io.archSnapshot.foreach { snapshot =>
    val pendingSnapshot = RegInit(false.B)
    snapshot.valid := pendingSnapshot && !io.busy && !io.flush
    when(snapshot.valid) { pendingSnapshot := false.B }
    when(io.completion.fire) { pendingSnapshot := true.B }
    snapshot.bits.vr := vectorBackend.io.debugVrf.get.asUInt.asTypeOf(snapshot.bits.vr)
    snapshot.bits.csr(0) := rvvVl
    snapshot.bits.csr(1) := Cat(rvvVill, 0.U(55.W), rvvVma, rvvVta, rvvSew, rvvVlmulSign, rvvVlmulMag)
    snapshot.bits.csr(2) := rvvVstart
    snapshot.bits.csr(3) := ep.vlenBytes.U
    snapshot.bits.csr(4) := rvvConfigured
  }
  val perfCycles = RegInit(0.U(64.W))
  val perfBusyCycles = RegInit(0.U(64.W))
  val perfStallCycles = RegInit(0.U(64.W))
  val perfMemRequests = RegInit(0.U(64.W))
  val perfMemResponses = RegInit(0.U(64.W))
  val perfCompletions = RegInit(0.U(64.W))
  val perfChained = RegInit(0.U(64.W))
  val perfMatrixBeats = RegInit(0.U(64.W))

  // Counters intentionally live after all fire/ready signals are defined.
  // They are reset by the same flush used for a benchmark epoch and do not
  // participate in datapath backpressure.
  when(io.flush) {
    perfCycles := 0.U
    perfBusyCycles := 0.U
    perfStallCycles := 0.U
    perfMemRequests := 0.U
    perfMemResponses := 0.U
    perfCompletions := 0.U
    perfChained := 0.U
    perfMatrixBeats := 0.U
  }.otherwise {
    perfCycles := perfCycles + 1.U
    when(io.busy) { perfBusyCycles := perfBusyCycles + 1.U }
    when(memReq.valid && !memReq.ready) { perfStallCycles := perfStallCycles + 1.U }
    when(memReq.fire) { perfMemRequests := perfMemRequests + 1.U }
    when(memResp.fire) { perfMemResponses := perfMemResponses + 1.U }
    when(io.completion.fire) { perfCompletions := perfCompletions + 1.U }
    when(issuedCommand.fire && issuedCommand.bits.chain) { perfChained := perfChained + 1.U }
    when(io.matrixInput.fire) { perfMatrixBeats := perfMatrixBeats + 1.U }
  }
  io.perf.cycles := perfCycles
  io.perf.busyCycles := perfBusyCycles
  io.perf.stallCycles := perfStallCycles
  io.perf.memRequests := perfMemRequests
  io.perf.memResponses := perfMemResponses
  io.perf.completions := perfCompletions
  io.perf.chained := perfChained
  io.perf.matrixBeats := perfMatrixBeats
}
