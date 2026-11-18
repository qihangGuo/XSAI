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
  val jobStatus  = Output(Vec(p.commandDepth, UInt(3.W)))
  val jobEpoch   = Output(UInt(2.W))
  val perf = Output(new EvuPerf)
  val storeTrace = Output(Valid(new EvuStoreTrace))
  val archSnapshot = Option.when(p.enableDifftest)(Output(Valid(new EvuArchSnapshot(p))))
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
  val memoryTagStalls = UInt(64.W)
  val vrfBankConflicts = UInt(64.W)
  val vrfWriteBlocksRead = UInt(64.W)
  val vrfOperandWait = UInt(64.W)
  val vrfPrefetches = UInt(64.W)
  val vrfReadGrantWait = UInt(64.W)
  val vrfReadResponseWait = UInt(64.W)
  val vrfDemandPrefetchWait = UInt(64.W)
  val vrfPrefetchDeferred = UInt(64.W)
  val vrfPrefetchUsed = UInt(64.W)
  val vrfPrefetchDiscarded = UInt(64.W)
  val scalarForwards = UInt(64.W)
  val memoryOrderStalls = UInt(64.W)
  val registerHazardStalls = UInt(64.W)
  val memoryComputeOverlap = UInt(64.W)
  val vmuLoadMerges = UInt(64.W)
  val vmuStoreMerges = UInt(64.W)
  val vmuCommandCycles = UInt(64.W)
  val vmuCommandPeak = UInt(64.W)
  val memoryTagCycles = UInt(64.W)
  val memoryTagPeak = UInt(64.W)
  val memoryGets = UInt(64.W)
  val memoryPuts = UInt(64.W)
  val vmuPartialWrites = UInt(64.W)
  val vmuBufferFull = UInt(64.W)
  val vmuAckWait = UInt(64.W)
  val vmuEntryStalls = UInt(64.W)
  val jobFullCycles = UInt(64.W)
  val jobHeadBlockedCycles = UInt(64.W)
  val jobFullReleaseCycles = UInt(64.W)
  val execution = new xiangshan.evu.common.EvuExecutionPerf
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
  private val (bus, edge)     = outer.node.out.head
  require(edge.bundle.dataBits == 512, "EVU must face HBL2 through TLWidthWidget(64)")
  require(ep.mlenBytes <= 64, "EVU logical requests must fit in a line packet")

  dispatchAdapter.io.in <> io.dispatch
  dispatchAdapter.io.flush := io.flush
  commandArbiter.io.in(0) <> io.command
  commandArbiter.io.in(1) <> dispatchAdapter.io.out
  val liveJobs = PopCount(jobValid)
  val hasJobSlot = !jobValid(nextJobId) && liveJobs < ep.commandDepth.U
  val vectorMemoryCommand = commandArbiter.io.out.bits.opcode === EvuOpcode.Load ||
    commandArbiter.io.out.bits.opcode === EvuOpcode.Store
  // The backend owns the bounded command window and per-command translations.
  // Old responses may drain while the next independent command starts.
  val issuedCommand = Wire(Decoupled(new EvuCommand(ep)))
  issuedCommand.valid := commandArbiter.io.out.valid && !io.flush && hasJobSlot &&
    !control.io.blockYounger
  issuedCommand.bits := commandArbiter.io.out.bits
  issuedCommand.bits.jobId := nextJobId
  issuedCommand.bits.epoch := currentEpoch
  val stateControl = commandArbiter.io.out.bits.opcode === EvuOpcode.SetVli ||
    commandArbiter.io.out.bits.opcode === EvuOpcode.SetVl
  val stateVectorOp = !stateControl &&
    commandArbiter.io.out.bits.opcode =/= EvuOpcode.Fence
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
  val controlRoute = Seq(EvuOpcode.Fence,
    EvuOpcode.SetVli, EvuOpcode.SetVl).map(issuedCommand.bits.opcode === _).reduce(_ || _)
  // The backend also completes invalid raw commands with Illegal, including VL=0.
  val saturnRoute = !controlRoute
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
    hasJobSlot && !control.io.blockYounger
  val completionArbiter = Module(new Arbiter(new EvuCompletion(ep), 2))
  completionArbiter.io.in(0) <> control.io.completion
  completionArbiter.io.in(1) <> vectorBackend.io.completion
  val completionQueue = Queue(completionArbiter.io.out, ep.completionDepth, flush = Some(io.flush))

  // Backend completion may be out of order (Saturn VAT release, VMU,
  // and control paths have different latencies). Keep a small
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
    acceptedOrder(acceptedTail) := nextJobId
    acceptedTail := acceptedTail + 1.U
  }
  when(!io.flush) {
    when(issuedCommand.fire && !io.completion.fire) { acceptedCount := acceptedCount + 1.U }
    when(io.completion.fire && !issuedCommand.fire) { acceptedCount := acceptedCount - 1.U }
  }
  control.io.flush := io.flush
  control.io.backendBusy := vectorBackend.io.busy || memoryBusy
  when(issuedCommand.fire) {
    when (stateControl && !useSnapshot) {
      rvvConfigured := true.B
      // The low eight bits of aux use the architectural vtype layout:
      // vlmul[2:0], vsew[5:3], vta[6], vma[7].
      val newVlmulSign = issuedCommand.bits.aux(2)
      val newVlmulMag = Mux(newVlmulSign, (8.U - issuedCommand.bits.aux(2, 0))(1, 0),
        issuedCommand.bits.aux(1, 0))
      val (newVl, effectiveSew, illegalVtype) = EvuVtype.configure(ep,
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
  }
  for (i <- 0 until ep.commandDepth) {
    when(jobValid(i) && jobState(i) === EvuJobState.Accepted && !io.flush) {
      jobState(i) := EvuJobState.Running
    }
  }
  io.jobStatus := jobState
  io.jobEpoch := currentEpoch
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
  }

  bus.b.ready := false.B
  bus.c.valid := false.B
  bus.c.bits  := DontCare
  bus.e.valid := false.B
  bus.e.bits  := DontCare
  bus.a.valid := false.B
  bus.a.bits  := DontCare
  bus.d.ready := false.B

  // Keep physical tag ownership separate from logical VMU requests, including
  // cancellation acknowledgements after the line adapter drains a flush.
  val tagRouter = Module(new EvuMemoryTagRouter(ep))
  val lineAdapter = Module(new EvuHbl2LineAdapter(ep))
  val vmuLines = Module(new EvuVmuLineCoalescer(ep))
  vmuLines.io.flush := io.flush
  vmuLines.io.storePending := vectorBackend.io.memoryStorePending
  vmuLines.io.drain := io.drain.request.valid || control.io.blockYounger
  tagRouter.io.flush := io.flush
  tagRouter.io.adapterBusy := lineAdapter.io.busy
  tagRouter.io.client <> vmuLines.io.memory
  val vectorReq = vmuLines.io.client.req
  vectorReq.valid := vectorBackend.io.memory.req.valid
  vectorBackend.io.memory.req.ready := vectorReq.ready
  vectorReq.bits.addr := vectorBackend.io.memory.req.bits.addr
  vectorReq.bits.data := vectorBackend.io.memory.req.bits.data
  vectorReq.bits.mask := vectorBackend.io.memory.req.bits.mask
  vectorReq.bits.fullLineData := false.B
  vectorReq.bits.bytes := ep.mlenBytes.U
  vectorReq.bits.write := vectorBackend.io.memory.req.bits.write
  vectorReq.bits.tag := vectorBackend.io.memory.req.bits.tag
  vectorReq.bits.jobId := vectorBackend.io.memory.req.bits.jobId
  vectorReq.bits.epoch := vectorBackend.io.memory.req.bits.epoch
  val vectorResp = vmuLines.io.client.resp
  vectorBackend.io.memory.resp.valid := vectorResp.valid
  vectorBackend.io.memory.resp.bits.data := vectorResp.bits.data
  vectorBackend.io.memory.resp.bits.error := vectorResp.bits.error
  vectorBackend.io.memory.resp.bits.fault := vectorResp.bits.fault
  vectorBackend.io.memory.resp.bits.tag := vectorResp.bits.tag
  vectorResp.ready := vectorBackend.io.memory.resp.ready
  val memReq = tagRouter.io.memory.req
  val memResp = tagRouter.io.memory.resp
  lineAdapter.io.client <> tagRouter.io.memory
  lineAdapter.io.flush := io.flush

  val physicalReq = lineAdapter.io.memory.req
  // VMU translation precedes physical-line merging; no mutable global map.
  val physicalAddress = physicalReq.bits.addr
  val (getLegal, getReq) = edge.Get(physicalReq.bits.tag, physicalAddress, 6.U)
  val (putLegal, putReq) = edge.Put(physicalReq.bits.tag, physicalAddress, 6.U,
    physicalReq.bits.data)
  val legal = edge.manager.containsSafe(physicalAddress) &&
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
    physicalStores(tag).mask := physicalReq.bits.mask
    tracedStore(tag) := physicalReq.bits.write
  }
  io.storeTrace.valid := bus.d.fire && tracedStore(bus.d.bits.source) &&
    !bus.d.bits.denied && !bus.d.bits.corrupt && !io.flush
  io.storeTrace.bits := physicalStores(bus.d.bits.source)
  when(bus.d.fire) { tracedStore(bus.d.bits.source) := false.B }
  when(io.flush) { tracedStore.foreach(_ := false.B) }
  lineAdapter.io.memory.resp <> busResponses.io.out
  memoryBusy := lineAdapter.io.busy || tagRouter.io.busy || vmuLines.io.busy
  when(bus.d.valid) {
    assert(bus.d.bits.source < ep.maxMemOutstanding.U, "EVU HBL2 response source out of range")
  }

  io.busy := control.io.busy || vectorBackend.io.busy ||
    dispatchAdapter.io.out.valid || io.dispatch.valid || memoryBusy || jobValid.asUInt.orR ||
    completionQueue.valid
  val drainPending = RegInit(false.B)
  io.drain.request.ready := !drainPending && !io.flush
  when(io.drain.request.fire) { drainPending := true.B }
  io.drain.response.valid := drainPending && !io.busy && !io.flush
  // Every ordinary command publishes its own flags/fault before busy clears.
  io.drain.response.bits := 0.U.asTypeOf(io.drain.response.bits)
  when(io.drain.response.fire) {
    drainPending := false.B
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
  val perfMemoryTagStalls = RegInit(0.U(64.W))
  val perfVmuLoadMerges = RegInit(0.U(64.W))
  val perfVmuStoreMerges = RegInit(0.U(64.W))

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
    perfMemoryTagStalls := 0.U
    perfVmuLoadMerges := 0.U
    perfVmuStoreMerges := 0.U
  }.otherwise {
    perfCycles := perfCycles + 1.U
    when(io.busy) { perfBusyCycles := perfBusyCycles + 1.U }
    when(memReq.valid && !memReq.ready) { perfStallCycles := perfStallCycles + 1.U }
    when(memReq.fire) { perfMemRequests := perfMemRequests + 1.U }
    when(memResp.fire) { perfMemResponses := perfMemResponses + 1.U }
    when(io.completion.fire) { perfCompletions := perfCompletions + 1.U }
    when(issuedCommand.fire && issuedCommand.bits.chain) { perfChained := perfChained + 1.U }
    when(tagRouter.io.tagStall) { perfMemoryTagStalls := perfMemoryTagStalls + 1.U }
    when(vmuLines.io.loadMerge) { perfVmuLoadMerges := perfVmuLoadMerges + 1.U }
    when(vmuLines.io.storeMerge) { perfVmuStoreMerges := perfVmuStoreMerges + 1.U }
  }
  io.perf.cycles := perfCycles
  io.perf.busyCycles := perfBusyCycles
  io.perf.stallCycles := perfStallCycles
  io.perf.memRequests := perfMemRequests
  io.perf.memResponses := perfMemResponses
  io.perf.completions := perfCompletions
  io.perf.chained := perfChained
  io.perf.memoryTagStalls := perfMemoryTagStalls
  io.perf.vrfBankConflicts := vectorBackend.io.vrfPerf.bankConflicts
  io.perf.vrfWriteBlocksRead := vectorBackend.io.vrfPerf.writeBlocksRead
  io.perf.vrfOperandWait := vectorBackend.io.vrfPerf.operandWait
  io.perf.vrfPrefetches := vectorBackend.io.vrfPerf.prefetches
  io.perf.vrfReadGrantWait := vectorBackend.io.vrfPerf.readGrantWait
  io.perf.vrfReadResponseWait := vectorBackend.io.vrfPerf.readResponseWait
  io.perf.vrfDemandPrefetchWait := vectorBackend.io.vrfPerf.demandPrefetchWait
  io.perf.vrfPrefetchDeferred := vectorBackend.io.vrfPerf.prefetchDeferred
  io.perf.vrfPrefetchUsed := vectorBackend.io.vrfPerf.prefetchUsed
  io.perf.vrfPrefetchDiscarded := vectorBackend.io.vrfPerf.prefetchDiscarded
  io.perf.scalarForwards := vectorBackend.io.vrfPerf.scalarForwards
  io.perf.memoryOrderStalls := vectorBackend.io.memoryOrderStalls
  io.perf.registerHazardStalls := vectorBackend.io.registerHazardStalls
  io.perf.memoryComputeOverlap := vectorBackend.io.memoryComputeOverlap
  io.perf.vmuLoadMerges := perfVmuLoadMerges
  io.perf.vmuStoreMerges := perfVmuStoreMerges
  val jobPerf = Module(new EvuJobPerf(ep.commandDepth, ep.enableDifftest))
  jobPerf.io.live := jobValid
  jobPerf.io.completed := completionByJobValid
  jobPerf.io.head := retireId
  jobPerf.io.headValid := acceptedCount =/= 0.U
  jobPerf.io.commandValid := commandArbiter.io.out.valid
  jobPerf.io.hasSlot := hasJobSlot
  jobPerf.io.retiring := io.completion.fire
  jobPerf.io.flush := io.flush
  io.perf.jobFullCycles := jobPerf.io.full
  io.perf.jobHeadBlockedCycles := jobPerf.io.headBlocked
  io.perf.jobFullReleaseCycles := jobPerf.io.fullRelease
  io.perf.execution := vectorBackend.io.executionPerf

  // Occupancy sums are tag-cycles/command-cycles, not transaction counts.
  val events = Seq(
    "command_cycles" -> vectorBackend.io.memoryOutstanding,
    "tag_cycles" -> tagRouter.io.occupancy,
    "get" -> (bus.a.fire && !physicalReq.bits.write).asUInt,
    "put" -> (bus.a.fire && physicalReq.bits.write).asUInt,
    "partial" -> (lineAdapter.io.partialWrite && tagRouter.io.client.req.fire).asUInt,
    "buffer_full" -> vmuLines.io.bufferFull.asUInt,
    "ack_wait" -> vmuLines.io.ackWait.asUInt,
    "entry_stall" -> (issuedCommand.valid && vectorMemoryCommand && !vectorBackend.io.command.ready).asUInt
  )
  val memoryCounters = events.map { case (_, increment) =>
    val counter = RegInit(0.U(64.W))
    when(io.flush) { counter := 0.U }.otherwise { counter := counter + increment }
    counter
  }
  val commandPeak = RegInit(0.U(64.W))
  val tagPeak = RegInit(0.U(64.W))
  when(io.flush) { commandPeak := 0.U; tagPeak := 0.U }.otherwise {
    commandPeak := commandPeak.max(vectorBackend.io.memoryOutstanding)
    tagPeak := tagPeak.max(tagRouter.io.occupancy)
  }
  io.perf.vmuCommandCycles := memoryCounters(0)
  io.perf.memoryTagCycles := memoryCounters(1)
  io.perf.memoryGets := memoryCounters(2)
  io.perf.memoryPuts := memoryCounters(3)
  io.perf.vmuPartialWrites := memoryCounters(4)
  io.perf.vmuBufferFull := memoryCounters(5)
  io.perf.vmuAckWait := memoryCounters(6)
  io.perf.vmuEntryStalls := memoryCounters(7)
  io.perf.vmuCommandPeak := commandPeak
  io.perf.memoryTagPeak := tagPeak
  if (ep.enableDifftest) {
    // Optional simulation-only snapshots at a completed FENCE. Subtract
    // adjacent snapshots around a kernel; no performance CSR/ISA change.
    val trace = freechips.rocketchip.util.PlusArg("evu_mem_trace", width = 1)
    val rmwGets = RegInit(0.U(64.W)).suggestName("vmuRmwGets")
    val rmwMerges = RegInit(0.U(64.W)).suggestName("vmuRmwMerges")
    when(vmuLines.io.rmwGet) { rmwGets := rmwGets + 1.U }
    when(vmuLines.io.rmwMerge) { rmwMerges := rmwMerges + 1.U }
    val fenceIndex = RegInit(0.U(32.W))
    when(io.completion.fire && io.completion.bits.opcode === EvuOpcode.Fence) {
      fenceIndex := fenceIndex + 1.U
      when(trace.asBool) {
        printf(p"[EVU-CONFIG] fence=${fenceIndex} dlen=${ep.dlenBits.U} mlen=${ep.mlenBits.U} vlen=${ep.vlenBits.U} fma_contexts=${ep.fmaOrdinaryContexts.U} reduction_contexts=${ep.reductionContexts.U} fp32_div_lanes=${(if (ep.parallelFp32Div) ep.dlenBits / 32 else 1).U} slide_prefetch=${ep.slidePrefetch.B}\n")
        printf(p"[EVU-JOB] fence=${fenceIndex} full=${jobPerf.io.full} head=${jobPerf.io.headBlocked} release=${jobPerf.io.fullRelease}\n")
        printf(p"[EVU-VMU] fence=${fenceIndex} t=${perfCycles} cmd=${memoryCounters(0)} tag=${memoryCounters(1)} get=${memoryCounters(2)} put=${memoryCounters(3)} partial=${memoryCounters(4)} full=${memoryCounters(5)} ack=${memoryCounters(6)} entry=${memoryCounters(7)} win=${vectorBackend.io.memoryOrderStalls} merge=${perfVmuStoreMerges} rmwget=${rmwGets} rmwmerge=${rmwMerges}\n")
        val exe = vectorBackend.io.executionPerf
        val vrf = vectorBackend.io.vrfPerf
        printf(p"[EVU-DISPATCH] fence=${fenceIndex} path=all queue=${exe.dispatchBlocked}\n")
        for ((name, index) <- xiangshan.evu.common.EvuDispatchPerf.names.zipWithIndex) {
          printf(p"[EVU-DISPATCH] fence=${fenceIndex} path=${name} queue=${exe.dispatchQueueWait(index)}\n")
        }
        printf(p"[EVU-EXE] fence=${fenceIndex} dep=${exe.dependencyWait} operand=${exe.operandWait} feedback=${exe.feedbackWait} perm=${exe.permutationWait} wb=${exe.writebackWait} fu=${exe.executionWait} grant=${vrf.readGrantWait} resp=${vrf.readResponseWait} e0=${vrf.scalarForwards} early=${exe.earlyOperandReads} rpf=${exe.reductionPrefetches} dpw=${vrf.demandPrefetchWait} defer=${vrf.prefetchDeferred} used=${vrf.prefetchUsed} drop=${vrf.prefetchDiscarded} bank=${vrf.bankConflicts} wrblk=${vrf.writeBlocksRead} vfw=${exe.vectorForwards} vnew=${exe.vectorForwardNewer}\n")
        for ((name, index) <- xiangshan.evu.common.EvuFuPerf.names.zipWithIndex) {
          val fu = exe.byFu(index)
          printf(p"[EVU-FU] fence=${fenceIndex} path=${name} issue=${fu.issue} busy=${fu.busy} dep=${fu.dependencyWait} operand=${fu.operandWait} feedback=${fu.feedbackWait} perm=${fu.permutationWait} wb=${fu.writebackWait} fu=${fu.executionWait} queue=${fu.queueWait} dispatch=${fu.dispatchBlocked} idle=${fu.idleWithWork} arb=${fu.readyArbitrationWait} qi=${fu.queueIdle} slot=${fu.resultSlotWait} pri=${fu.priorityIssues} nowork=${fu.noWork} loadwait=${fu.loadWait} fwait=${fu.forwardWait}\n")
          printf(p"[EVU-SCHED] fence=${fenceIndex} path=${name} ordinary=${fu.ordinaryFull} ordinary_idle=${fu.ordinaryFullIdle} reduction=${fu.reductionFull} ready_idle=${fu.operandsReadyIdle} eligible_idle=${fu.eligibleIdle} losers=${fu.readyLosers} bank_reserve=${fu.bankReservation} contexts=${fu.contextCycles} dispatch_red=${fu.dispatchReduction}\n")
          printf(p"[EVU-HANDOFF] fence=${fenceIndex} path=${name} eligible=${fu.lookaheadEligible} idle=${fu.lookaheadIdle} slide=${fu.slideLookaheadEligible}\n")
          for ((stage, j) <- xiangshan.evu.common.EvuReductionStage.names.zipWithIndex) {
            val s = fu.stages(j)
            printf(p"[EVU-RED] fence=${fenceIndex} path=${name} stage=${stage} issue=${s.issues} e16=${s.elements16} e32=${s.elements32} feedback=${s.feedback} operand=${s.operands}\n")
            printf(p"[EVU-TAIL] fence=${fenceIndex} path=${name} stage=${stage} ready_wait=${s.resultReadyWait} return_issue=${s.returnIssues}\n")
          }
        }
        for ((name, index) <- xiangshan.evu.common.EvuOpClass.names.zipWithIndex) {
          val op = exe.byOp(index)
          printf(p"[EVU-OP] fence=${fenceIndex} op=${name} inst=${op.instructions} issue=${op.issues} e16=${op.elements16} e32=${op.elements32} dep=${op.dependency} operand=${op.operands} feedback=${op.feedback} completed=${op.completed} latency=${op.latency}\n")
        }
        for ((name, index) <- xiangshan.evu.common.EvuReadClass.names.zipWithIndex) {
          val r = vrf.byRead(index)
          printf(p"[EVU-READ] fence=${fenceIndex} path=${name} request=${r.requests} grant=${r.grants} read_block=${r.readBlocked} write_block=${r.writeBlocked} response=${r.responseWait} held=${r.held} pf_used=${r.prefetchUsed}\n")
          printf(p"[EVU-READHINT] fence=${fenceIndex} path=${name} last_grant=${r.lastGranted} last_block=${r.lastBlocked}\n")
        }
        val mem = vectorBackend.io.memoryDiagnostic
        printf(p"[EVU-MEM] fence=${fenceIndex} load=${mem.loads} store=${mem.stores} load_bytes=${mem.loadBytes} store_bytes=${mem.storeBytes} logical_load=${mem.logicalLoadBytes} logical_store=${mem.logicalStoreBytes} no_tag=${mem.noTag} downstream=${mem.downstream} load_lost=${mem.loadLost} store_lost=${mem.storeLost} response_block=${mem.responseBlocked} cancelled=${mem.cancelled}\n")
        for (b <- 0 until 4) {
          printf(p"[EVU-LAT] fence=${fenceIndex} bucket=${b.U} load=${mem.loadLatency(b)} store=${mem.storeLatency(b)}\n")
        }
      }
    }
  }
}
