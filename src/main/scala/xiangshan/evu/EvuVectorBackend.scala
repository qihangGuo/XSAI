package xiangshan.evu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu.adapter.{EvuVectorParameters, EvuVectorSubsystem}
import xiangshan.evu.common.{OPIFunct6, OPMFunct6, OPFFunct6, VectorConsts, VectorParams, VectorWrite}
import xiangshan.evu.insns.FEXP2_V

object EvuSaturnMemory {
  def encode(cmd: EvuCommand): UInt = {
    val width = MuxLookup(cmd.sew, 0.U(3.W))(Seq(
      0.U -> "b000".U(3.W), 1.U -> "b101".U(3.W),
      2.U -> "b110".U(3.W)
    ))
    val bits = Cat(0.U(3.W), 0.U(1.W),
      Mux(cmd.stride === 0.S, VectorConsts.mopUnit, VectorConsts.mopStrided),
      1.U(1.W), 0.U(5.W), 0.U(5.W), width, cmd.vd,
      Mux(cmd.opcode === EvuOpcode.Store, VectorConsts.opcStore, VectorConsts.opcLoad))
    require(bits.getWidth == 32, "Saturn memory encoding must retain every instruction bit")
    bits
  }
}

object EvuSaturnGather {
  def encodeImmediate(cmd: EvuCommand): UInt = Cat(
    OPIFunct6.rgather.asUInt,
    Mux(cmd.aux(1), cmd.aux(0), true.B),
    cmd.vs2, cmd.vs1, VectorConsts.OPIVI, cmd.vd, VectorConsts.opcVector
  )
}

class EvuVectorBackendIO(p: EvuParams, unifiedCommands: Boolean) extends Bundle {
  val command = Flipped(Decoupled(new EvuCommand(p)))
  val completion = Decoupled(new EvuCompletion(p))
  val memory = new EvuMemoryIO(p)
  val flush = Input(Bool())
  val busy = Output(Bool())
  val debugVrf = Option.when(p.enableDifftest)(Output(Vec(p.vectorRegs * p.vlenBits / p.dlenBits, UInt(p.dlenBits.W))))
  val vta = Input(Bool())
  val vma = Input(Bool())
  val vlmulSign = Input(Bool())
  val vlmulMag = Input(UInt(2.W))
  val vstart = Input(UInt(log2Ceil(p.vlenBits + 1).W))
  val vill = Input(Bool())
  val vrfPerf = Output(new xiangshan.evu.backend.VrfPerf)
  val executionPerf = Output(new xiangshan.evu.common.EvuExecutionPerf)
  val memoryOrderStalls = Output(UInt(64.W))
  val registerHazardStalls = Output(UInt(64.W))
  val memoryComputeOverlap = Output(UInt(64.W))
  val memoryOutstanding = Output(UInt(log2Ceil(p.memoryCommandDepth + 1).W))
  val memoryStorePending = Output(Bool())
  val memoryDiagnostic = Output(new xiangshan.evu.common.EvuMemoryDiagnostic)
  val commandHead = Option.when(unifiedCommands)(Input(UInt(log2Ceil(p.commandDepth).W)))
  val commandTail = Option.when(unifiedCommands)(Input(UInt(log2Ceil(p.commandDepth).W)))
  val commandEpoch = Option.when(unifiedCommands)(Input(UInt(2.W)))
  val scheduleRelease = Option.when(unifiedCommands)(Output(UInt(p.commandDepth.W)))
  val traceId = Option.when(unifiedCommands && p.enableDifftest)(Output(UInt(log2Ceil(p.commandDepth).W)))
  val tracePc = Option.when(unifiedCommands && p.enableDifftest)(Input(UInt(64.W)))
}

/**
  * Maps XSAI xv.* commands onto the Saturn-style EVU execution engine.
  *
  * Saturn is the EVU execution engine: its issue queues, element-group
  * chaining and RegisterFile are the only architectural vector state. This
  * adapter translates command metadata without any external VRF consumers.
  * EvuTop supplies unified command age tags and owns ordered completion metadata.
  * Standalone backend harnesses may retain Saturn-local tags. Load/store tags
  * are remapped into one HBL2 source-ID namespace in both modes.
  */
class EvuVectorBackend(p: EvuParams, unifiedCommands: Boolean = false)(implicit xsParameters: Parameters) extends Module {
  private val saturnParams = VectorParams.lgvParams.copy(
    vatSz = if (unifiedCommands) log2Ceil(p.commandDepth) else VectorParams.lgvParams.vatSz,
    externalCommandTags = unifiedCommands,
    vdqEntries = p.dispatchDepth,
    // LoadOrderBuffer requires the in-flight queue to cover the response
    // ROB. The EVU HBL2 tag budget is only the outstanding-request limit, so
    // it may be smaller than the response bookkeeping depth.
    vlifqEntries = p.loadResponseDepth max p.maxMemOutstanding,
    vsifqEntries = p.maxMemOutstanding,
    vlrobEntries = p.loadResponseDepth,
    vlissqEntries = p.loadIssueDepth,
    vsissqEntries = p.storeIssueDepth,
    vxissqEntries = p.executeIssueDepth,
    vpissqEntries = p.specialIssueDepth,
    // Reservation groups match the physical banks in each 64-bit lane.
    // Read arbitration and write reservations must use the same mapping.
    vrfBanking = p.vrfBanks,
    debugVrf = p.enableDifftest,
    diagnosticCounters = p.enableDifftest,
    enableChaining = true,
    enableOOO = true,
    pipelinedVatClear = true,
    issStructure = if (p.splitFpIssue) xiangshan.evu.common.VectorIssueStructure.SplitFP else VectorParams.lgvParams.issStructure,
    reductionContexts = p.reductionContexts,
    fmaOrdinaryContexts = p.fmaOrdinaryContexts,
    parallelFp32Div = p.parallelFp32Div,
    interleaveReductions = p.interleaveReductions,
    fp32MinMaxTree = p.fp32MinMaxTree,
    singleGroupMinMax = p.singleGroupMinMax,
    alignedSlide = p.alignedSlide,
    singleGroupSlide = p.singleGroupSlide,
    crossInstructionPrefetch = p.crossInstructionPrefetch,
    slidePrefetch = p.slidePrefetch,
    extendedResultForwarding = p.extendedResultForwarding,
    shortResultForwarding = p.shortResultForwarding
  )
  private implicit val saturnP: Parameters = EvuVectorParameters(
    xsParameters,
    p.vlenBits,
    p.dlenBits,
    p.mlenBits,
    saturnParams
  )
  private val vatEntries = 1 << saturnParams.vatSz

  val io = IO(new EvuVectorBackendIO(p, unifiedCommands))
  private val saturn = withReset(reset.asBool || io.flush) {
    Module(new EvuVectorSubsystem()(saturnP))
  }
  saturn.io.commandHead.foreach(_ := io.commandHead.get)
  saturn.io.commandTail.foreach(_ := io.commandTail.get)
  io.scheduleRelease.foreach(_ := saturn.io.scheduleRelease.get)
  io.debugVrf.foreach(_ := saturn.io.debugVrf.get)
  io.vrfPerf := saturn.io.vrfPerf
  io.executionPerf := saturn.io.executionPerf
  val cmd = io.command.bits
  val localVl = Mux(cmd.aux(6), Mux(cmd.vl === 0.U || io.vstart =/= 0.U, 0.U, 1.U), cmd.vl)
  private val zeroCompletionPending = RegInit(false.B)
  private val zeroCompletionReg = RegInit(0.U.asTypeOf(new EvuCommandResult(p)))
  private val zeroIdentity = Option.when(!unifiedCommands)(Reg(new EvuCommandIdentity))

  private def isSaturnOpcode(opcode: UInt): Bool = Seq(
    EvuOpcode.Add, EvuOpcode.Sub, EvuOpcode.And, EvuOpcode.Or, EvuOpcode.Xor,
    EvuOpcode.Mul, EvuOpcode.Mac, EvuOpcode.ReduceSum, EvuOpcode.ReduceMax,
    EvuOpcode.ReduceMin, EvuOpcode.Sll, EvuOpcode.Srl, EvuOpcode.Sra,
    EvuOpcode.Min, EvuOpcode.Max, EvuOpcode.MinU, EvuOpcode.MaxU,
    EvuOpcode.FAdd, EvuOpcode.FSub, EvuOpcode.FMul, EvuOpcode.FMacc,
    EvuOpcode.FDiv, EvuOpcode.FMin, EvuOpcode.FMax, EvuOpcode.FReduceSum,
    EvuOpcode.FReduceMin, EvuOpcode.FReduceMax, EvuOpcode.WAdd, EvuOpcode.WSub,
    EvuOpcode.WMul, EvuOpcode.SatAdd, EvuOpcode.SatSub, EvuOpcode.SlideUp,
    EvuOpcode.SlideDown, EvuOpcode.ReduceAnd, EvuOpcode.ReduceOr, EvuOpcode.ReduceXor,
    EvuOpcode.Load, EvuOpcode.Store, EvuOpcode.Gather, EvuOpcode.GatherImm, EvuOpcode.Exp2
  ).map(opcode === _).reduce(_ || _) || EvuRvvMath.contains(opcode) || EvuLowPrecision.contains(opcode)

  val isMemory = cmd.opcode === EvuOpcode.Load || cmd.opcode === EvuOpcode.Store
  val nextVat = if (unifiedCommands) cmd.jobId else RegInit(0.U(saturnParams.vatSz.W))
  // Standalone mode protects metadata until physical completion. In unified
  // mode the command table owns allocation through CPU reporting instead.
  val contextLive = Option.when(!unifiedCommands)(RegInit(VecInit.fill(vatEntries)(false.B)))
  val contextAvailable = contextLive.map(live => !live(nextVat)).getOrElse(true.B)
  io.memoryStorePending := saturn.io.memoryStorePending
  val isExp2 = cmd.opcode === EvuOpcode.Exp2
  val isGatherImm = cmd.opcode === EvuOpcode.GatherImm
  val isSlide = EvuIsa.isSlide(cmd.opcode)
  val supportedWidth = EvuIsa.supportsElementWidth(cmd.opcode, cmd.sew)
  val isOpm = Seq(EvuOpcode.Mul, EvuOpcode.Mac, EvuOpcode.ReduceSum, EvuOpcode.ReduceMax, EvuOpcode.ReduceMin, EvuOpcode.WAdd, EvuOpcode.WSub, EvuOpcode.WMul, EvuOpcode.ReduceAnd, EvuOpcode.ReduceOr, EvuOpcode.ReduceXor).map(cmd.opcode === _).reduce(_ || _)
  val isOpf = Seq(EvuOpcode.FAdd, EvuOpcode.FSub, EvuOpcode.FMul, EvuOpcode.FMacc, EvuOpcode.FDiv, EvuOpcode.FMin, EvuOpcode.FMax, EvuOpcode.FReduceSum, EvuOpcode.FReduceMin, EvuOpcode.FReduceMax, EvuOpcode.Exp2).map(cmd.opcode === _).reduce(_ || _)
  val opiFunct6 = MuxLookup(cmd.opcode, OPIFunct6.add.asUInt)(Seq(
    EvuOpcode.Add -> OPIFunct6.add.asUInt,
    EvuOpcode.Sub -> OPIFunct6.sub.asUInt,
    EvuOpcode.And -> OPIFunct6.and.asUInt,
    EvuOpcode.Or -> OPIFunct6.or.asUInt,
    EvuOpcode.Xor -> OPIFunct6.xor.asUInt,
    EvuOpcode.Sll -> OPIFunct6.sll.asUInt,
    EvuOpcode.Srl -> OPIFunct6.srl.asUInt,
    EvuOpcode.Sra -> OPIFunct6.sra.asUInt,
    EvuOpcode.MinU -> OPIFunct6.minu.asUInt,
    EvuOpcode.Min -> OPIFunct6.min.asUInt,
    EvuOpcode.MaxU -> OPIFunct6.maxu.asUInt,
    EvuOpcode.Max -> OPIFunct6.max.asUInt,
    EvuOpcode.SatAdd -> OPIFunct6.sadd.asUInt,
    EvuOpcode.SatSub -> OPIFunct6.ssub.asUInt,
    EvuOpcode.Gather -> OPIFunct6.rgather.asUInt
  ))
  val opmFunct6 = MuxLookup(cmd.opcode, OPMFunct6.mul.asUInt)(Seq(
    EvuOpcode.Mul -> OPMFunct6.mul.asUInt,
    EvuOpcode.Mac -> OPMFunct6.macc.asUInt,
    EvuOpcode.ReduceSum -> OPMFunct6.redsum.asUInt,
    EvuOpcode.ReduceMax -> OPMFunct6.redmax.asUInt,
    EvuOpcode.ReduceMin -> OPMFunct6.redmin.asUInt,
    EvuOpcode.WAdd -> OPMFunct6.wadd.asUInt,
    EvuOpcode.WSub -> OPMFunct6.wsub.asUInt,
    EvuOpcode.WMul -> OPMFunct6.wmul.asUInt,
    EvuOpcode.ReduceAnd -> OPMFunct6.redand.asUInt,
    EvuOpcode.ReduceOr -> OPMFunct6.redor.asUInt,
    EvuOpcode.ReduceXor -> OPMFunct6.redxor.asUInt
  ))
  val opfFunct6 = MuxLookup(cmd.opcode, OPFFunct6.fadd.asUInt)(Seq(
    EvuOpcode.FAdd -> OPFFunct6.fadd.asUInt,
    EvuOpcode.FSub -> OPFFunct6.fsub.asUInt,
    EvuOpcode.FMul -> OPFFunct6.fmul.asUInt,
    EvuOpcode.FMacc -> OPFFunct6.fmacc.asUInt,
    EvuOpcode.FDiv -> OPFFunct6.fdiv.asUInt,
    EvuOpcode.FMin -> OPFFunct6.fmin.asUInt,
    EvuOpcode.FMax -> OPFFunct6.fmax.asUInt,
    EvuOpcode.FReduceSum -> OPFFunct6.fredusum.asUInt,
    EvuOpcode.FReduceMin -> OPFFunct6.fredmin.asUInt,
    EvuOpcode.FReduceMax -> OPFFunct6.fredmax.asUInt,
    EvuOpcode.Exp2 -> OPFFunct6.funary1.asUInt
  ))
  val arithmeticBits = Mux(EvuLowPrecision.contains(cmd.opcode), EvuLowPrecision.encode(cmd),
    Mux(EvuRvvMath.contains(cmd.opcode), EvuRvvMath.encode(cmd),
    Mux(isSlide, EvuSaturnSlide.encode(cmd),
    Mux(isGatherImm, EvuSaturnGather.encodeImmediate(cmd), Cat(
    Mux(isOpf, opfFunct6, Mux(isOpm, opmFunct6, opiFunct6)),
    // Custom xv commands encode {mask-enable, vm} in aux[1:0].  Existing
    // Saturn-routed commands leave aux[1] clear and therefore remain
    // unmasked by default.
    Mux(cmd.aux(1), cmd.aux(0), true.B),
    cmd.vs2,
    Mux(isExp2, FEXP2_V.selector.U(5.W), cmd.vs1),
    Mux(isOpf, VectorConsts.OPFVV, Mux(isOpm, VectorConsts.OPMVV, VectorConsts.OPIVV)),
    cmd.vd,
    VectorConsts.opcVector
  )))))
  val memoryBits = EvuSaturnMemory.encode(cmd)

  // Invalid, retired and unsupported commands must complete with an error,
  // even at VL=0. They must not wait for a removed special execution path.
  val unsupportedOpcode = !isSaturnOpcode(cmd.opcode)
  // RVV permits VL=0 as a completed no-op. Do not send it into Saturn's
  // sequencer (which quite correctly expects at least one element); emit a
  // normal ordered completion instead.
  val zeroCommand = io.command.valid &&
    isSaturnOpcode(cmd.opcode) && (localVl === 0.U || (isExp2 && io.vstart >= localVl))
  // Width errors are completed in order, just like VL=0 no-ops.  They must
  // never enter a sequencer after its 64-bit element datapaths were removed.
  val unsupportedWidthCommand = io.command.valid && !supportedWidth &&
    cmd.opcode =/= EvuOpcode.SetVli && cmd.opcode =/= EvuOpcode.SetVl
  val encodedLmul = Mux(io.vlmulSign, (8.U - io.vlmulMag)(2, 0), io.vlmulMag.pad(3))
  val invalidMathGroup = EvuRvvMath.contains(cmd.opcode) &&
    (!EvuRvvMath.validGroups(cmd.opcode, cmd.vd, cmd.vs1, cmd.vs2, encodedLmul) ||
      (cmd.aux(6) && (encodedLmul =/= 0.U || cmd.sew =/= 2.U)) || cmd.aux(1))
  val invalidLowPrecision = EvuLowPrecision.contains(cmd.opcode) &&
    (!EvuLowPrecision.validGroups(cmd.opcode, cmd.vd, cmd.vs1, cmd.vs2, encodedLmul) ||
      cmd.aux =/= 0.U || io.vstart =/= 0.U || cmd.vl > (p.vlenBits / 32).U)
  val invalidSlideGroup = isSlide &&
    !EvuSaturnSlide.validGroups(cmd.opcode, cmd.vd, cmd.vs2, encodedLmul)
  val illegalCommand = unsupportedOpcode || io.vill || !supportedWidth || invalidMathGroup || invalidLowPrecision || invalidSlideGroup
  val immediateCompletion = zeroCommand || unsupportedWidthCommand ||
    (io.command.valid && (unsupportedOpcode || io.vill || invalidMathGroup || invalidLowPrecision || invalidSlideGroup))

  // Keep command-window credits and non-chainable register reservations
  // until completion. Saturn's VAT handles ordinary element-group hazards.
  private val reservations = Module(new EvuCommandScoreboard(p))

  val commandReadMask = WireDefault(0.U(p.vectorRegs.W))
  val commandWriteMask = WireDefault(0.U(p.vectorRegs.W))
  val groupRegisters = Mux(io.vlmulSign, 1.U(5.W), (1.U(5.W) << io.vlmulMag)(4, 0))
  val widening = Seq(EvuOpcode.WAdd, EvuOpcode.WSub, EvuOpcode.WMul).map(cmd.opcode === _).reduce(_ || _)
  val reduction = Seq(EvuOpcode.ReduceSum, EvuOpcode.ReduceMax, EvuOpcode.ReduceMin,
    EvuOpcode.ReduceAnd, EvuOpcode.ReduceOr, EvuOpcode.ReduceXor,
    EvuOpcode.FReduceSum, EvuOpcode.FReduceMin, EvuOpcode.FReduceMax).map(cmd.opcode === _).reduce(_ || _)
  val destinationRegisters = Mux(EvuRvvMath.contains(cmd.opcode),
    EvuRvvMath.destinationRegisters(cmd.opcode, encodedLmul), Mux(reduction, 1.U,
      Mux(widening && !io.vlmulSign, groupRegisters << 1, groupRegisters)))
  val sourceRegisters = Mux(EvuRvvMath.contains(cmd.opcode),
    EvuRvvMath.sourceRegisters(cmd.opcode, encodedLmul), groupRegisters)
  def registerGroup(reg: UInt, count: UInt): UInt = VecInit((0 until p.vectorRegs).map { i =>
    i.U >= reg && i.U < (reg +& count)
  }).asUInt
  when (isSaturnOpcode(cmd.opcode) && !illegalCommand) {
    commandReadMask := registerGroup(cmd.vs1, Mux(reduction, 1.U, groupRegisters)) |
      registerGroup(cmd.vs2, sourceRegisters) |
      Mux(cmd.opcode === EvuOpcode.Mac || cmd.opcode === EvuOpcode.FMacc,
        registerGroup(cmd.vd, destinationRegisters), 0.U) |
      Mux(cmd.aux(1) && !cmd.aux(0), UIntToOH(0.U, p.vectorRegs), 0.U)
    when (isExp2 || isGatherImm || isSlide || EvuRvvMath.unary(cmd.opcode) || EvuRvvMath.immediate(cmd.opcode) ||
        EvuLowPrecision.unary(cmd.opcode)) {
      commandReadMask := registerGroup(cmd.vs2, sourceRegisters) |
        Mux(cmd.aux(1) && !cmd.aux(0), UIntToOH(0.U, p.vectorRegs), 0.U)
    }
    when (cmd.opcode === EvuOpcode.Load) {
      commandReadMask := 0.U
      commandWriteMask := registerGroup(cmd.vd, groupRegisters)
    }.elsewhen (cmd.opcode === EvuOpcode.Store) {
      commandReadMask := registerGroup(cmd.vd, groupRegisters)
    }.otherwise {
      commandWriteMask := registerGroup(cmd.vd, destinationRegisters)
    }
  }
  // Bound memory commands independently of arithmetic and retain EG hazards.
  reservations.io.commandValid := io.command.valid
  reservations.io.readMask := commandReadMask
  reservations.io.writeMask := commandWriteMask
  reservations.io.isMemory := isMemory
  reservations.io.chainable := isSaturnOpcode(cmd.opcode) && !immediateCompletion
  reservations.io.jobId := cmd.jobId
  reservations.io.accept := io.command.fire
  reservations.io.release.valid := io.completion.fire
  reservations.io.release.bits := io.completion.bits.jobId
  reservations.io.flush := io.flush
  val commandHazardFree = reservations.io.allowed
  io.memoryOrderStalls := reservations.io.memoryOrderStalls
  io.registerHazardStalls := reservations.io.registerHazardStalls
  io.memoryComputeOverlap := reservations.io.memoryComputeOverlap
  io.memoryOutstanding := reservations.io.memoryOutstanding

  saturn.io.issue.valid := io.command.valid && isSaturnOpcode(cmd.opcode) &&
    !immediateCompletion && supportedWidth && commandHazardFree && contextAvailable && !io.flush
  val backendReady = Mux(immediateCompletion, !zeroCompletionPending,
    saturn.io.issue.ready && contextAvailable && isSaturnOpcode(cmd.opcode) && supportedWidth &&
      cmd.vl =/= 0.U && !io.flush)
  io.command.ready := backendReady && commandHazardFree && !io.flush
  saturn.io.issue.bits := 0.U.asTypeOf(saturn.io.issue.bits)
  if (unifiedCommands) { saturn.io.issue.bits.vat := cmd.jobId }
  saturn.io.issue.bits.bits := Mux(isMemory, memoryBits, arithmeticBits)
  saturn.io.issue.bits.vconfig.vl := localVl
  saturn.io.issue.bits.scalarVrfSource := cmd.aux(5) || EvuLowPrecision.scaled(cmd.opcode)
  saturn.io.issue.bits.vconfig.vtype.vill := io.vill
  saturn.io.issue.bits.vconfig.vtype.reserved := 0.U
  saturn.io.issue.bits.vconfig.vtype.vma := io.vma
  saturn.io.issue.bits.vconfig.vtype.vta := io.vta
  saturn.io.issue.bits.vconfig.vtype.vsew := cmd.sew
  saturn.io.issue.bits.vconfig.vtype.vlmul_sign := io.vlmulSign
  saturn.io.issue.bits.vconfig.vtype.vlmul_mag := io.vlmulMag
  saturn.io.issue.bits.fission_vl.valid := false.B
  saturn.io.issue.bits.vstart := Mux(cmd.aux(6), 0.U, io.vstart)
  saturn.io.issue.bits.segstart := 0.U
  saturn.io.issue.bits.segend := 0.U
  saturn.io.issue.bits.rs1_data := cmd.base
  saturn.io.issue.bits.rs2_data := cmd.stride.asUInt
  // Mapped commands use a page-relative address internally, avoiding any
  // truncation of canonical virtual addresses to Saturn's physical width.
  saturn.io.issue.bits.page := Mux(cmd.pageMap.enabled, 0.U, cmd.base >> 12)
  saturn.io.issue.bits.conservativeMemoryOrdering := cmd.pageMap.enabled
  saturn.io.issue.bits.rm := 0.U
  saturn.io.issue.bits.emul := Mux(io.vlmulSign, 0.U, io.vlmulMag)
  saturn.io.issue.bits.fast_sg := false.B
  saturn.io.issue.bits.mop := Mux(cmd.stride === 0.S, VectorConsts.mopUnit, VectorConsts.mopStrided)

  // Only standalone mode translates local VATs back to external identities.
  // Production EvuTop stores that identity once in its unified command table.
  val jobIdByVat = Option.when(!unifiedCommands)(Reg(Vec(vatEntries, UInt(log2Ceil(p.commandDepth).W))))
  val pcByVat = Option.when(!unifiedCommands)(Reg(Vec(vatEntries, UInt(64.W))))
  val memoryByVat = Reg(Vec(vatEntries, Bool()))
  val opcodeByVat = Option.when(!unifiedCommands)(Reg(Vec(vatEntries, UInt(8.W))))
  val vdByVat = Option.when(!unifiedCommands)(Reg(Vec(vatEntries, UInt(p.regIndexWidth.W))))
  val epochByVat = Option.when(!unifiedCommands)(Reg(Vec(vatEntries, UInt(2.W))))
  def jobForVat(vat: UInt): UInt = jobIdByVat.map(_(vat)).getOrElse(vat)
  def epochForVat(vat: UInt): UInt = epochByVat.map(_(vat)).getOrElse(io.commandEpoch.get)
  val pageMapByVat = Reg(Vec(vatEntries, new EvuPageMap(p)))
  val virtualBaseByVat = Reg(Vec(vatEntries, UInt(64.W)))
  val memoryErrorAddress = RegInit(VecInit.fill(vatEntries)(0.U(64.W)))
  val memoryErrorSticky = RegInit(VecInit.fill(vatEntries)(false.B))
  val memoryFaultSticky = RegInit(VecInit.fill(vatEntries)(EvuFault.None))
  // The final store Ack can release its VAT in the same cycle. Forward its
  // error into completion rather than waiting for the sticky registers.
  val returningMemoryFault = Wire(Valid(new Bundle {
    val vat = UInt(saturnParams.vatSz.W)
    val address = UInt(64.W)
    val cause = UInt(4.W)
  }))
  when(io.command.fire) {
    when (immediateCompletion) {
      zeroIdentity.foreach { identity =>
        identity.pc := cmd.pc
        identity.opcode := cmd.opcode
        identity.vd := cmd.vd
        identity.epoch := cmd.epoch
      }
      zeroCompletionReg.outcome.tval := 0.U
      zeroCompletionReg.outcome.tvalValid := false.B
      zeroCompletionReg.outcome.exception := illegalCommand
      zeroCompletionReg.outcome.fflags := 0.U
      zeroCompletionReg.outcome.vxsat := false.B
      // Like the reference, conservatively dirty arithmetic contexts even for
      // an empty ALU operation. Zero-length memory operations leave VS alone.
      zeroCompletionReg.outcome.dirtyVs := !illegalCommand &&
        cmd.opcode =/= EvuOpcode.Load && cmd.opcode =/= EvuOpcode.Store
      zeroCompletionReg.outcome.cause := Mux(illegalCommand, EvuFault.Illegal, EvuFault.None)
      zeroCompletionReg.jobId := cmd.jobId
      zeroCompletionReg.epoch := cmd.epoch
      zeroCompletionPending := true.B
    }
  }
  when(saturn.io.issue.fire) {
    contextLive.foreach { live =>
      assert(!live(nextVat), "EVU reused metadata before physical completion")
      live(nextVat) := true.B
    }
    pcByVat.foreach(_(nextVat) := cmd.pc)
    memoryByVat(nextVat) := isMemory
    jobIdByVat.foreach(_(nextVat) := cmd.jobId)
    opcodeByVat.foreach(_(nextVat) := cmd.opcode)
    vdByVat.foreach(_(nextVat) := cmd.vd)
    epochByVat.foreach(_(nextVat) := cmd.epoch)
    pageMapByVat(nextVat) := cmd.pageMap
    virtualBaseByVat(nextVat) := Mux(cmd.pageMap.enabled, cmd.base & ~4095.U(64.W), 0.U)
    memoryErrorSticky(nextVat) := false.B
    memoryFaultSticky(nextVat) := EvuFault.None
    if (!unifiedCommands) { nextVat := nextVat + 1.U }
  }
  if (!unifiedCommands) { when(io.flush) { nextVat := 0.U } }

  val releasedThisCycle = saturn.io.completed.map { release =>
    Mux(release.valid, UIntToOH(release.bits, vatEntries), 0.U(vatEntries.W))
  }.reduce(_ | _)
  val pendingReleases = RegInit(0.U(vatEntries.W))
  val completedVxsat = Reg(Vec(vatEntries, Bool()))
  val completedFflags = Reg(Vec(vatEntries, UInt(5.W)))
  for ((release, i) <- saturn.io.completed.zipWithIndex) {
    when (release.valid) {
      completedFflags(release.bits) := saturn.io.completionFflags(i)
      completedVxsat(release.bits) := saturn.io.completionVxsat(i)
    }
  }
  val visibleReleases = pendingReleases | releasedThisCycle
  val completionVat = PriorityEncoder(visibleReleases)
  val completionFaultNow = returningMemoryFault.valid && returningMemoryFault.bits.vat === completionVat
  val completionMemoryError = memoryErrorSticky(completionVat) || completionFaultNow
  val saturnCompletion = Wire(Decoupled(new EvuCompletion(p)))
  saturnCompletion.valid := visibleReleases.orR && !io.flush
  saturnCompletion.bits.opcode := opcodeByVat.map(_(completionVat)).getOrElse(0.U)
  saturnCompletion.bits.vd := vdByVat.map(_(completionVat)).getOrElse(0.U)
  saturnCompletion.bits.pc := pcByVat.map(_(completionVat)).getOrElse(0.U)
  saturnCompletion.bits.tval := Mux(memoryErrorSticky(completionVat),
    memoryErrorAddress(completionVat), Mux(completionFaultNow, returningMemoryFault.bits.address, 0.U))
  saturnCompletion.bits.tvalValid := memoryByVat(completionVat) && completionMemoryError
  saturnCompletion.bits.exception := memoryByVat(completionVat) && completionMemoryError
  saturnCompletion.bits.cause := Mux(memoryByVat(completionVat) && completionMemoryError,
    Mux(memoryErrorSticky(completionVat), memoryFaultSticky(completionVat), returningMemoryFault.bits.cause),
    EvuFault.None)
  val releasingCompletion = saturn.io.completed.map(r => r.valid && r.bits === completionVat)
  saturnCompletion.bits.fflags := Mux(releasingCompletion.reduce(_ || _),
    Mux1H(releasingCompletion, saturn.io.completionFflags), completedFflags(completionVat))
  saturnCompletion.bits.vxsat := Mux(releasingCompletion.reduce(_ || _),
    Mux1H(releasingCompletion, saturn.io.completionVxsat), completedVxsat(completionVat))
  saturnCompletion.bits.dirtyVs := opcodeByVat.map(_(completionVat) =/= EvuOpcode.Store).getOrElse(true.B)
  saturnCompletion.bits.jobId := jobForVat(completionVat)
  saturnCompletion.bits.epoch := epochForVat(completionVat)
  io.traceId.foreach(_ := completionVat)
  val zeroCompletion = Wire(Decoupled(new EvuCompletion(p)))
  zeroCompletion.valid := zeroCompletionPending && !io.flush
  zeroCompletion.bits := 0.U.asTypeOf(zeroCompletion.bits)
  zeroCompletion.bits.pc := zeroIdentity.map(_.pc).getOrElse(0.U)
  zeroCompletion.bits.opcode := zeroIdentity.map(_.opcode).getOrElse(0.U)
  zeroCompletion.bits.vd := zeroIdentity.map(_.vd).getOrElse(0.U)
  zeroCompletion.bits.jobId := zeroCompletionReg.jobId
  zeroCompletion.bits.epoch := zeroCompletionReg.epoch
  zeroCompletion.bits.tval := zeroCompletionReg.outcome.tval
  zeroCompletion.bits.tvalValid := zeroCompletionReg.outcome.tvalValid
  zeroCompletion.bits.exception := zeroCompletionReg.outcome.exception
  zeroCompletion.bits.fflags := zeroCompletionReg.outcome.fflags
  zeroCompletion.bits.vxsat := zeroCompletionReg.outcome.vxsat
  zeroCompletion.bits.dirtyVs := zeroCompletionReg.outcome.dirtyVs
  zeroCompletion.bits.cause := zeroCompletionReg.outcome.cause
  val completionArbiter = Module(new Arbiter(new EvuCompletion(p), 2))
  completionArbiter.io.in(0) <> saturnCompletion
  completionArbiter.io.in(1) <> zeroCompletion
  io.completion <> completionArbiter.io.out
  when (zeroCompletion.fire) { zeroCompletionPending := false.B }
  when(io.flush) {
    contextLive.foreach(_.foreach(_ := false.B))
    pendingReleases := 0.U
    memoryErrorSticky.foreach(_ := false.B)
    zeroCompletionPending := false.B
    memoryFaultSticky.foreach(_ := EvuFault.None)
  }.otherwise {
    when(saturnCompletion.fire) { contextLive.foreach(_(completionVat) := false.B) }
    pendingReleases := visibleReleases & ~Mux(saturnCompletion.fire, UIntToOH(completionVat, vatEntries), 0.U(vatEntries.W))
    when(saturnCompletion.fire && memoryByVat(completionVat)) {
      memoryErrorSticky(completionVat) := false.B
      memoryFaultSticky(completionVat) := EvuFault.None
    }
  }

  saturn.io.scalarResp.ready := true.B

  // Saturn has independent load and store tag spaces. Remap both onto the
  // shared HBL2 source-ID space and remember how to route each response.
  val hblInFlight = RegInit(VecInit.fill(p.maxMemOutstanding)(false.B))
  val hblAddress = Reg(Vec(p.maxMemOutstanding, UInt(64.W)))
  val hblIsStore = Reg(Vec(p.maxMemOutstanding, Bool()))
  // Saturn queue indices can be wider than the external HBL2 source IDs.
  val hblLocalTag = Reg(Vec(p.maxMemOutstanding, chiselTypeOf(saturn.io.dmem.load_req.bits.tag)))
  val hblDrop = RegInit(VecInit.fill(p.maxMemOutstanding)(false.B))
  val hblVat = Reg(Vec(p.maxMemOutstanding, UInt(saturnParams.vatSz.W)))
  private val saturnSlots = p.maxMemOutstanding
  require(saturnSlots > 0)
  // These are client-local IDs; line coalescing and the tag router retain
  // physical ownership independently until Ack or completed flush drain.
  val freeMask = ((BigInt(1) << saturnSlots) - 1).U(p.maxMemOutstanding.W) & ~hblInFlight.asUInt
  val hasFreeTag = freeMask.orR
  val freeTag = PriorityEncoder(freeMask)
  val loadPending = saturn.io.dmem.load_req.valid
  val storePending = saturn.io.dmem.store_req.valid
  // Saturn's ready store already passed its ordering/data guards. Near a
  // full command window, let it advance after two load grants instead of
  // eight. Use registered occupancy, not the completion-credit bypass.
  val requestArbiter = Module(new EvuVmuRequestArbiter)
  requestArbiter.io.loadPending := loadPending
  requestArbiter.io.storePending := storePending
  requestArbiter.io.windowPressure := reservations.io.memoryOutstanding >=
    (p.memoryCommandDepth - 1).max(1).U
  requestArbiter.io.grant := io.memory.req.fire
  requestArbiter.io.flush := io.flush
  val chooseStore = requestArbiter.io.chooseStore
  val requestVat = Mux(chooseStore, saturn.io.dmem.store_req.bits.vat, saturn.io.dmem.load_req.bits.vat)
  val requestVirtualAddress = virtualBaseByVat(requestVat) +
    (Mux(chooseStore, saturn.io.dmem.store_req.bits.addr, saturn.io.dmem.load_req.bits.addr) &
      ~(p.mlenBytes - 1).U(64.W))
  val translate = Module(new EvuPageTranslate(p))
  translate.io.pageMap := pageMapByVat(requestVat)
  translate.io.vaddr := requestVirtualAddress & ~63.U(64.W)

  io.memory.req.valid := hasFreeTag &&
    (saturn.io.dmem.load_req.valid || saturn.io.dmem.store_req.valid) && !io.flush
  io.memory.req.bits.addr := translate.io.paddr | requestVirtualAddress(5, 0)
  io.memory.req.bits.jobId := jobForVat(requestVat)
  io.memory.req.bits.epoch := epochForVat(requestVat)
  when(io.memory.req.valid) { assert(translate.io.legal, "VMU request escaped its preflight page map") }
  io.memory.req.bits.data := Mux(chooseStore, saturn.io.dmem.store_req.bits.data, 0.U)
  io.memory.req.bits.mask := Mux(chooseStore, saturn.io.dmem.store_req.bits.mask,
    ((BigInt(1) << p.mlenBytes) - 1).U)
  io.memory.req.bits.write := chooseStore
  io.memory.req.bits.tag := freeTag
  saturn.io.dmem.load_req.ready := io.memory.req.ready && hasFreeTag && !chooseStore && !io.flush
  saturn.io.dmem.store_req.ready := io.memory.req.ready && hasFreeTag && chooseStore && !io.flush

  when(io.memory.req.fire) {
    hblAddress(freeTag) := requestVirtualAddress
    hblVat(freeTag) := requestVat
    hblInFlight(freeTag) := true.B
    hblDrop(freeTag) := false.B
    hblIsStore(freeTag) := chooseStore
    hblLocalTag(freeTag) := Mux(chooseStore, saturn.io.dmem.store_req.bits.tag, saturn.io.dmem.load_req.bits.tag)
  }

  val responseTag = io.memory.resp.bits.tag
  val responseLive = hblInFlight(responseTag)
  val responseStore = hblIsStore(responseTag)
  val responseDrop = hblDrop(responseTag)
  saturn.io.dmem.load_resp.valid := io.memory.resp.valid && responseLive && !responseStore && !responseDrop && !io.flush
  saturn.io.dmem.load_resp.bits.data := io.memory.resp.bits.data
  saturn.io.dmem.load_resp.bits.tag := hblLocalTag(responseTag)
  saturn.io.dmem.store_ack.valid := io.memory.resp.valid && responseLive && responseStore && !responseDrop && !io.flush
  saturn.io.dmem.store_ack.bits.data := io.memory.resp.bits.data
  saturn.io.dmem.store_ack.bits.tag := hblLocalTag(responseTag)
  io.memory.resp.ready := responseLive && Mux(
    responseDrop || io.flush,
    true.B,
    Mux(responseStore, saturn.io.dmem.store_ack.ready, saturn.io.dmem.load_resp.ready)
  )
  returningMemoryFault.valid := io.memory.resp.fire && io.memory.resp.bits.error && !responseDrop && !io.flush
  returningMemoryFault.bits.vat := hblVat(responseTag)
  returningMemoryFault.bits.address := hblAddress(responseTag)
  returningMemoryFault.bits.cause := io.memory.resp.bits.fault
  when(io.memory.resp.fire) {
    // Cancelled responses still reclaim their tags, but cannot fault a new
    // memory owner after flush (or resurrect a fault on the flush edge).
    when(returningMemoryFault.valid && !(saturnCompletion.fire && completionVat === hblVat(responseTag))) {
      val vat = hblVat(responseTag)
      when(!memoryErrorSticky(vat)) {
        memoryErrorAddress(vat) := hblAddress(responseTag)
        memoryFaultSticky(vat) := io.memory.resp.bits.fault
      }
      memoryErrorSticky(vat) := true.B
    }
    hblInFlight(responseTag) := false.B
    hblDrop(responseTag) := false.B
  }
  when(io.flush) {
    for (tag <- 0 until p.maxMemOutstanding) {
      when(hblInFlight(tag) && !(io.memory.resp.fire && responseTag === tag.U)) {
        hblDrop(tag) := true.B
      }
    }
  }

  io.memoryDiagnostic := 0.U.asTypeOf(io.memoryDiagnostic)
  if (p.enableDifftest) {
    val trace = freechips.rocketchip.util.PlusArg("evu_cmd_trace", width = 1)
    val ticks = RegInit(0.U(64.W)).suggestName("diag_memoryTicks")
    ticks := ticks + 1.U
    def count(name: String, increment: UInt): UInt = {
      val value = RegInit(0.U(64.W)).suggestName(s"diag_memory_$name")
      when(io.flush) { value := 0.U }.otherwise { value := value + increment }
      value
    }
    val stats = io.memoryDiagnostic
    val issued = io.memory.req.fire
    stats.loads := count("loads", issued && !chooseStore)
    stats.stores := count("stores", issued && chooseStore)
    stats.loadBytes := count("loadBytes", Mux(issued && !chooseStore, p.mlenBytes.U, 0.U))
    stats.storeBytes := count("storeBytes", Mux(issued && chooseStore, PopCount(io.memory.req.bits.mask), 0.U))
    val logicalBytes = localVl << cmd.sew
    stats.logicalLoadBytes := count("logicalLoadBytes", Mux(saturn.io.issue.fire &&
      cmd.opcode === EvuOpcode.Load, logicalBytes, 0.U))
    stats.logicalStoreBytes := count("logicalStoreBytes", Mux(saturn.io.issue.fire &&
      cmd.opcode === EvuOpcode.Store, logicalBytes, 0.U))
    stats.noTag := count("noTag", (loadPending || storePending) && !hasFreeTag)
    stats.downstream := count("downstream", io.memory.req.valid && !io.memory.req.ready)
    stats.loadLost := count("loadLost", issued && loadPending && chooseStore)
    stats.storeLost := count("storeLost", issued && storePending && !chooseStore)
    stats.responseBlocked := count("responseBlocked", io.memory.resp.valid && responseLive &&
      !responseDrop && !io.memory.resp.ready)
    val sentAt = Reg(Vec(p.maxMemOutstanding, UInt(64.W))).suggestName("diag_memorySentAt")
    when(issued) { sentAt(freeTag) := ticks }
    val latency = ticks - sentAt(responseTag)
    val bucket = Mux(latency < 16.U, 0.U, Mux(latency < 64.U, 1.U, Mux(latency < 256.U, 2.U, 3.U)))
    val response = io.memory.resp.fire && !responseDrop && !io.flush
    for (b <- 0 until 4) {
      stats.loadLatency(b) := count(s"loadLatency$b", response && !responseStore && bucket === b.U)
      stats.storeLatency(b) := count(s"storeLatency$b", response && responseStore && bucket === b.U)
    }
    stats.cancelled := count("cancelled", io.memory.resp.fire && responseDrop)
    val ticket = RegInit(0.U(64.W)).suggestName("diag_ticket")
    val tickets = Reg(Vec(vatEntries, UInt(64.W))).suggestName("diag_tickets")
    val acceptedAt = Reg(Vec(vatEntries, UInt(64.W))).suggestName("diag_commandAccepted")
    when(saturn.io.issue.fire) {
      tickets(nextVat) := ticket
      acceptedAt(nextVat) := ticks
      ticket := ticket + 1.U
      when(trace.asBool) {
        printf(p"[EVU-CMD] event=accept ticket=${ticket} vat=${nextVat} job=${cmd.jobId} epoch=${cmd.epoch} pc=0x${Hexadecimal(cmd.pc)} opcode=${cmd.opcode} t=${ticks}\n")
      }
    }
    when(saturnCompletion.fire && trace.asBool) {
      val completedPc = if (unifiedCommands) io.tracePc.get else pcByVat.get(completionVat)
      printf(p"[EVU-CMD] event=complete ticket=${tickets(completionVat)} vat=${completionVat} job=${jobForVat(completionVat)} epoch=${epochForVat(completionVat)} pc=0x${Hexadecimal(completedPc)} total=${ticks - acceptedAt(completionVat)} t=${ticks}\n")
    }
    when(io.flush && trace.asBool) { printf(p"[EVU-CMD] event=flush t=${ticks}\n") }
  }

  io.busy := saturn.io.busy || hblInFlight.asUInt.orR ||
    pendingReleases.orR || zeroCompletionPending || reservations.io.busy
}
