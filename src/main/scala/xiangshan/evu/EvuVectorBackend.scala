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

class EvuVectorBackendIO(p: EvuParams) extends Bundle {
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
  val cuteWrite = Decoupled(new EvuCuteWrite(p))
  val matrixInput = Flipped(Decoupled(new MatrixMicroBlock(p)))
  val matrixOutput = Decoupled(new MatrixMicroBlock(p))
  val matrixFflags = Output(UInt(5.W))
}

/**
  * Maps XSAI xv.* commands onto the Saturn-style EVU execution engine.
  *
  * Saturn is the EVU execution engine: its issue queues, element-group
  * chaining and RegisterFile are the only architectural vector state. This
  * adapter translates command metadata and exposes the same VRF to fused paths.
  * It maps VAT releases back to EVU job IDs and remaps the independent load/store tags into one
  * HBL2 source-ID namespace.
  */
class EvuVectorBackend(p: EvuParams)(implicit xsParameters: Parameters) extends Module {
  private val saturnParams = VectorParams.lgvParams.copy(
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
    // The physical Ara-style organization has eight banks per 64-bit lane.
    // Expose that bank count to the write reservation logic as well; using
    // the old four-wide logical count would unnecessarily serialize banks
    // b and b+4.
    vrfBanking = 8,
    debugVrf = p.enableDifftest,
    enableChaining = true,
    enableOOO = true,
    pipelinedVatClear = true
  )
  private implicit val saturnP: Parameters = EvuVectorParameters(
    xsParameters,
    p.vlenBits,
    p.dlenBits,
    p.mlenBits,
    saturnParams
  )
  private val vatEntries = 1 << saturnParams.vatSz

  val io = IO(new EvuVectorBackendIO(p))
  private val saturn = withReset(reset.asBool || io.flush) {
    Module(new EvuVectorSubsystem()(saturnP))
  }
  private val special = Module(new EvuSpecialSequencer(p))
  io.debugVrf.foreach(_ := saturn.io.debugVrf.get)
  val cmd = io.command.bits
  private val zeroCompletionPending = RegInit(false.B)
  private val zeroCompletionReg = RegInit(0.U.asTypeOf(new EvuCompletion(p)))

  // Translate the EVU architectural (register, DLEN chunk) view used by the
  // special sequencer to Saturn's element-group address space.
  private val specialChunkBits = log2Ceil(p.vlenBits / p.dlenBits)
  for (i <- 0 until 3) {
    saturn.io.special.read(i).req.valid := special.io.vrf.read(i).valid && !io.flush
    saturn.io.special.read(i).req.bits.eg :=
      (special.io.vrf.read(i).bits.addr << specialChunkBits) + special.io.vrf.read(i).bits.chunk
    saturn.io.special.read(i).req.bits.oldest := special.io.vrf.read(i).bits.oldest
    special.io.vrf.read(i).ready := saturn.io.special.read(i).req.ready && !io.flush
    special.io.vrf.readData(i) := saturn.io.special.read(i).resp
  }
  special.io.flush := io.flush
  special.io.cuteWrite <> io.cuteWrite
  // The matrix input connection is installed after the cross-path hazard
  // table below.  This prevents a CUTE parameter read from racing a Saturn
  // write in the same cycle.
  io.matrixOutput <> special.io.matrix.out
  io.matrixFflags := special.io.matrix.fflags

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
    EvuOpcode.Load, EvuOpcode.Store, EvuOpcode.Gather, EvuOpcode.Exp2
  ).map(opcode === _).reduce(_ || _)

  val isMemory = cmd.opcode === EvuOpcode.Load || cmd.opcode === EvuOpcode.Store
  val isExp2 = cmd.opcode === EvuOpcode.Exp2
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
    EvuOpcode.SlideUp -> OPIFunct6.slideup.asUInt,
    EvuOpcode.SlideDown -> OPIFunct6.slidedown.asUInt,
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
  val arithmeticBits = Cat(
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
  )
  val memoryBits = EvuSaturnMemory.encode(cmd)

  val isSpecialOpcode = Seq(EvuOpcode.QuantToCute,
    EvuOpcode.SetCfg, EvuOpcode.Scatter,
    EvuOpcode.Invalid).map(cmd.opcode === _).reduce(_ || _)
  // RVV permits VL=0 as a completed no-op. Do not send it into Saturn's
  // sequencer (which quite correctly expects at least one element); emit a
  // normal ordered completion instead.
  val zeroCommand = io.command.valid && !isSpecialOpcode &&
    isSaturnOpcode(cmd.opcode) && (cmd.vl === 0.U || (isExp2 && io.vstart >= cmd.vl))
  // Width errors are completed in order, just like VL=0 no-ops.  They must
  // never enter a sequencer after its 64-bit element datapaths were removed.
  val unsupportedWidthCommand = io.command.valid && !supportedWidth &&
    cmd.opcode =/= EvuOpcode.SetVli && cmd.opcode =/= EvuOpcode.SetVl
  val immediateCompletion = zeroCommand || unsupportedWidthCommand

  // Cross-path register hazards. Saturn's VAT tracks ordinary Saturn
  // operations internally; these entries extend that contract to the fused
  // special sequencer and CUTE parameter reads. One entry is kept per EVU job
  // so an early completion cannot clear a later command's reservation.
  private val allRegMask = ((BigInt(1) << p.vectorRegs) - 1).U(p.vectorRegs.W)
  private val memoryLive = RegInit(false.B)
  private val memoryJobId = Reg(UInt(log2Ceil(p.commandDepth).W))
  private val hazardValid = RegInit(VecInit.fill(p.commandDepth)(false.B))
  private val hazardRead = Reg(Vec(p.commandDepth, UInt(p.vectorRegs.W)))
  private val hazardWrite = Reg(Vec(p.commandDepth, UInt(p.vectorRegs.W)))
  private val matrixHazardCount = RegInit(0.U(2.W))
  private val matrixHazardHead = RegInit(0.U(1.W))
  private val matrixHazardTail = RegInit(0.U(1.W))
  private val matrixHazardRead = Reg(Vec(2, UInt(p.vectorRegs.W)))
  val busyReadMask = hazardValid.zip(hazardRead).map { case (v, m) => Mux(v, m, 0.U) }.reduce(_ | _)
  val busyWriteMask = hazardValid.zip(hazardWrite).map { case (v, m) => Mux(v, m, 0.U) }.reduce(_ | _)
  val incomingMatrixMask = UIntToOH(io.matrixInput.bits.scaleTag(4, 0), p.vectorRegs) |
    UIntToOH(io.matrixInput.bits.biasTag(4, 0), p.vectorRegs)
  val matrixReadMask = Wire(UInt(p.vectorRegs.W))
  matrixReadMask := 0.U
  when (matrixHazardCount =/= 0.U) {
    matrixReadMask := matrixHazardRead(0) | matrixHazardRead(1)
  }

  val commandReadMask = WireDefault(0.U(p.vectorRegs.W))
  val commandWriteMask = WireDefault(0.U(p.vectorRegs.W))
  val groupRegisters = Mux(io.vlmulSign, 1.U(5.W), (1.U(5.W) << io.vlmulMag)(4, 0))
  val widening = Seq(EvuOpcode.WAdd, EvuOpcode.WSub, EvuOpcode.WMul).map(cmd.opcode === _).reduce(_ || _)
  val reduction = Seq(EvuOpcode.ReduceSum, EvuOpcode.ReduceMax, EvuOpcode.ReduceMin,
    EvuOpcode.ReduceAnd, EvuOpcode.ReduceOr, EvuOpcode.ReduceXor,
    EvuOpcode.FReduceSum, EvuOpcode.FReduceMin, EvuOpcode.FReduceMax).map(cmd.opcode === _).reduce(_ || _)
  val destinationRegisters = Mux(reduction, 1.U,
    Mux(widening && !io.vlmulSign, groupRegisters << 1, groupRegisters))
  def registerGroup(reg: UInt, count: UInt): UInt = VecInit((0 until p.vectorRegs).map { i =>
    i.U >= reg && i.U < (reg +& count)
  }).asUInt
  when (isSpecialOpcode) {
    when (cmd.opcode =/= EvuOpcode.SetCfg &&
      cmd.opcode =/= EvuOpcode.Scatter && cmd.opcode =/= EvuOpcode.Invalid) {
      // Descriptor scale/bias registers are selected dynamically by aux.
      // Reserve reads conservatively until the descriptor sources are
      // available here. QuantToCute never writes the Saturn VRF.
      commandReadMask := allRegMask
    }
  }.elsewhen (isSaturnOpcode(cmd.opcode)) {
    commandReadMask := registerGroup(cmd.vs1, Mux(reduction, 1.U, groupRegisters)) |
      registerGroup(cmd.vs2, groupRegisters) |
      Mux(cmd.aux(1) && !cmd.aux(0), UIntToOH(0.U, p.vectorRegs), 0.U)
    when (isExp2) {
      commandReadMask := registerGroup(cmd.vs2, groupRegisters) |
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
  val memoryOrderReady = !memoryLive && (!isMemory || !hazardValid.asUInt.orR)
  val commandHazardFree = memoryOrderReady &&
    !((commandReadMask & busyWriteMask).orR) &&
    !((commandWriteMask & (busyReadMask | busyWriteMask)).orR) &&
    !((commandWriteMask & matrixReadMask).orR)

  // Matrix/CUTE consumes scale and bias through the same VRF. It has no
  // architectural write, but its read reservation must remain until the
  // transformed beat leaves the special sequencer.
  val matrixInputAllowed = matrixHazardCount < 2.U &&
    !((incomingMatrixMask & busyWriteMask).orR)

  special.io.matrix.in.valid := io.matrixInput.valid && matrixInputAllowed && !io.command.valid && !io.flush
  special.io.matrix.in.bits := io.matrixInput.bits
  io.matrixInput.ready := special.io.matrix.in.ready && matrixInputAllowed &&
    !io.command.valid && !io.flush

  saturn.io.issue.valid := io.command.valid && isSaturnOpcode(cmd.opcode) &&
    !isSpecialOpcode && !immediateCompletion && supportedWidth && commandHazardFree && !io.flush
  special.io.command.valid := io.command.valid && isSpecialOpcode && supportedWidth &&
    commandHazardFree && !io.flush
  special.io.command.bits := cmd
  val backendReady = Mux(immediateCompletion, !zeroCompletionPending,
    Mux(isSpecialOpcode, special.io.command.ready && supportedWidth,
      saturn.io.issue.ready && isSaturnOpcode(cmd.opcode) && supportedWidth &&
        cmd.vl =/= 0.U && !io.flush))
  io.command.ready := backendReady && commandHazardFree
  saturn.io.issue.bits := 0.U.asTypeOf(saturn.io.issue.bits)
  saturn.io.issue.bits.bits := Mux(isMemory, memoryBits, arithmeticBits)
  saturn.io.issue.bits.vconfig.vl := cmd.vl
  saturn.io.issue.bits.vconfig.vtype.vill := io.vill
  saturn.io.issue.bits.vconfig.vtype.reserved := 0.U
  saturn.io.issue.bits.vconfig.vtype.vma := io.vma
  saturn.io.issue.bits.vconfig.vtype.vta := io.vta
  saturn.io.issue.bits.vconfig.vtype.vsew := cmd.sew
  saturn.io.issue.bits.vconfig.vtype.vlmul_sign := io.vlmulSign
  saturn.io.issue.bits.vconfig.vtype.vlmul_mag := io.vlmulMag
  saturn.io.issue.bits.fission_vl.valid := false.B
  saturn.io.issue.bits.vstart := io.vstart
  saturn.io.issue.bits.segstart := 0.U
  saturn.io.issue.bits.segend := 0.U
  saturn.io.issue.bits.rs1_data := cmd.base
  saturn.io.issue.bits.rs2_data := cmd.stride.asUInt
  // Mapped commands use a page-relative address internally, avoiding any
  // truncation of canonical virtual addresses to Saturn's physical width.
  saturn.io.issue.bits.page := Mux(cmd.pageMap.enabled, 0.U, cmd.base >> 12)
  saturn.io.issue.bits.rm := 0.U
  saturn.io.issue.bits.emul := 0.U
  saturn.io.issue.bits.fast_sg := false.B
  saturn.io.issue.bits.mop := Mux(cmd.stride === 0.S, VectorConsts.mopUnit, VectorConsts.mopStrided)

  // The dispatcher allocates VATs in-order. Mirroring its tail at command
  // acceptance lets an out-of-order VAT release recover the original job ID.
  val nextVat = RegInit(0.U(saturnParams.vatSz.W))
  val jobIdByVat = Reg(Vec(vatEntries, UInt(log2Ceil(p.commandDepth).W)))
  val pcByVat = Reg(Vec(vatEntries, UInt(64.W)))
  val memoryByVat = Reg(Vec(vatEntries, Bool()))
  val opcodeByVat = Reg(Vec(vatEntries, UInt(8.W)))
  val vdByVat = Reg(Vec(vatEntries, UInt(p.regIndexWidth.W)))
  val epochByVat = Reg(Vec(vatEntries, UInt(2.W)))
  when(io.command.fire) {
    when(isMemory) { memoryLive := true.B; memoryJobId := cmd.jobId }
    hazardValid(cmd.jobId) := true.B
    hazardRead(cmd.jobId) := commandReadMask
    hazardWrite(cmd.jobId) := commandWriteMask
    when (immediateCompletion) {
      zeroCompletionReg.pc := cmd.pc
      zeroCompletionReg.tval := 0.U
      zeroCompletionReg.tvalValid := false.B
      zeroCompletionReg.opcode := cmd.opcode
      zeroCompletionReg.vd := cmd.vd
      zeroCompletionReg.exception := io.vill || !supportedWidth
      zeroCompletionReg.fflags := 0.U
      zeroCompletionReg.vxsat := false.B
      // Like the reference, conservatively dirty arithmetic contexts even for
      // an empty ALU operation. Zero-length memory operations leave VS alone.
      zeroCompletionReg.dirtyVs := !io.vill && supportedWidth &&
        cmd.opcode =/= EvuOpcode.Load && cmd.opcode =/= EvuOpcode.Store
      zeroCompletionReg.cause := Mux(io.vill || !supportedWidth, EvuFault.Illegal, EvuFault.None)
      zeroCompletionReg.jobId := cmd.jobId
      zeroCompletionReg.epoch := cmd.epoch
      zeroCompletionPending := true.B
    }
  }
  when(saturn.io.issue.fire) {
    pcByVat(nextVat) := cmd.pc
    memoryByVat(nextVat) := isMemory
    jobIdByVat(nextVat) := cmd.jobId
    opcodeByVat(nextVat) := cmd.opcode
    vdByVat(nextVat) := cmd.vd
    epochByVat(nextVat) := cmd.epoch
    nextVat := nextVat + 1.U
  }
  when(io.flush) { nextVat := 0.U }

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
  val memoryErrorAddress = RegInit(0.U(64.W))
  val memoryErrorSticky = RegInit(false.B)
  val memoryFaultSticky = RegInit(EvuFault.None)
  val visibleReleases = pendingReleases | releasedThisCycle
  val completionVat = PriorityEncoder(visibleReleases)
  val saturnCompletion = Wire(Decoupled(new EvuCompletion(p)))
  saturnCompletion.valid := visibleReleases.orR && !io.flush
  saturnCompletion.bits.opcode := opcodeByVat(completionVat)
  saturnCompletion.bits.vd := vdByVat(completionVat)
  saturnCompletion.bits.pc := pcByVat(completionVat)
  saturnCompletion.bits.tval := memoryErrorAddress
  saturnCompletion.bits.tvalValid := memoryByVat(completionVat) && memoryErrorSticky
  saturnCompletion.bits.exception := memoryByVat(completionVat) && memoryErrorSticky
  saturnCompletion.bits.cause := Mux(memoryByVat(completionVat), memoryFaultSticky, EvuFault.None)
  val releasingCompletion = saturn.io.completed.map(r => r.valid && r.bits === completionVat)
  saturnCompletion.bits.fflags := Mux(releasingCompletion.reduce(_ || _),
    Mux1H(releasingCompletion, saturn.io.completionFflags), completedFflags(completionVat))
  saturnCompletion.bits.vxsat := Mux(releasingCompletion.reduce(_ || _),
    Mux1H(releasingCompletion, saturn.io.completionVxsat), completedVxsat(completionVat))
  saturnCompletion.bits.dirtyVs := opcodeByVat(completionVat) =/= EvuOpcode.Store
  saturnCompletion.bits.jobId := jobIdByVat(completionVat)
  saturnCompletion.bits.epoch := epochByVat(completionVat)
  val zeroCompletion = Wire(Decoupled(new EvuCompletion(p)))
  zeroCompletion.valid := zeroCompletionPending && !io.flush
  zeroCompletion.bits := zeroCompletionReg
  val completionArbiter = Module(new Arbiter(new EvuCompletion(p), 3))
  completionArbiter.io.in(0) <> saturnCompletion
  completionArbiter.io.in(1) <> special.io.completion
  completionArbiter.io.in(2) <> zeroCompletion
  io.completion <> completionArbiter.io.out
  when (zeroCompletion.fire) { zeroCompletionPending := false.B }
  when(io.completion.fire) {
    when(memoryLive && io.completion.bits.jobId === memoryJobId) { memoryLive := false.B }
    for (i <- 0 until p.commandDepth) {
      when(hazardValid(i) && i.U === io.completion.bits.jobId) {
        hazardValid(i) := false.B
      }
    }
  }
  val matrixEnq = io.matrixInput.fire
  val matrixDeq = io.matrixOutput.fire
  when(matrixEnq) {
    matrixHazardRead(matrixHazardTail) := incomingMatrixMask
    matrixHazardTail := matrixHazardTail + 1.U
  }
  when(matrixDeq) {
    matrixHazardHead := matrixHazardHead + 1.U
  }
  when(matrixEnq && !matrixDeq) {
    matrixHazardCount := matrixHazardCount + 1.U
  }.elsewhen(matrixDeq && !matrixEnq) {
    matrixHazardCount := matrixHazardCount - 1.U
  }
  when(io.flush) {
    memoryLive := false.B
    pendingReleases := 0.U
    memoryErrorSticky := false.B
    zeroCompletionPending := false.B
    memoryFaultSticky := EvuFault.None
    for (i <- 0 until p.commandDepth) { hazardValid(i) := false.B }
    matrixHazardCount := 0.U
    matrixHazardHead := 0.U
    matrixHazardTail := 0.U
  }.otherwise {
    pendingReleases := visibleReleases & ~Mux(saturnCompletion.fire, UIntToOH(completionVat, vatEntries), 0.U(vatEntries.W))
    when(saturnCompletion.fire && memoryByVat(completionVat)) {
      memoryErrorSticky := false.B
      memoryFaultSticky := EvuFault.None
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
  private val saturnSlots = p.maxMemOutstanding - 1
  require(saturnSlots > 0)
  // The top-level EVU reserves global ID max-1 for CUTE output. Saturn owns
  // the remaining low range and uses its local tag directly.
  val freeMask = ((BigInt(1) << saturnSlots) - 1).U(p.maxMemOutstanding.W) & ~hblInFlight.asUInt
  val hasFreeTag = freeMask.orR
  val freeTag = PriorityEncoder(freeMask)
  val chooseStore = !saturn.io.dmem.load_req.valid && saturn.io.dmem.store_req.valid
  val virtualBase = RegInit(0.U(64.W))
  when(io.command.fire && isMemory) {
    virtualBase := Mux(cmd.pageMap.enabled, cmd.base & ~4095.U(64.W), 0.U)
  }

  io.memory.req.valid := hasFreeTag &&
    (saturn.io.dmem.load_req.valid || saturn.io.dmem.store_req.valid) && !io.flush
  io.memory.req.bits.addr := virtualBase +
    (Mux(chooseStore, saturn.io.dmem.store_req.bits.addr, saturn.io.dmem.load_req.bits.addr) &
      ~(p.mlenBytes - 1).U(64.W))
  io.memory.req.bits.data := Mux(chooseStore, saturn.io.dmem.store_req.bits.data, 0.U)
  io.memory.req.bits.mask := Mux(chooseStore, saturn.io.dmem.store_req.bits.mask,
    ((BigInt(1) << p.mlenBytes) - 1).U)
  io.memory.req.bits.write := chooseStore
  io.memory.req.bits.tag := freeTag
  saturn.io.dmem.load_req.ready := io.memory.req.ready && hasFreeTag && !chooseStore && !io.flush
  saturn.io.dmem.store_req.ready := io.memory.req.ready && hasFreeTag && chooseStore && !io.flush

  when(io.memory.req.fire) {
    hblAddress(freeTag) := io.memory.req.bits.addr
    hblInFlight(freeTag) := true.B
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
  when(io.memory.resp.fire) {
    when(io.memory.resp.bits.error) {
      when(!memoryErrorSticky) { memoryErrorAddress := hblAddress(responseTag) }
      memoryErrorSticky := true.B
      memoryFaultSticky := io.memory.resp.bits.fault
    }
    hblInFlight(responseTag) := false.B
    hblDrop(responseTag) := false.B
  }
  when(io.flush) {
    for (tag <- 0 until p.maxMemOutstanding) {
      when(hblInFlight(tag)) { hblDrop(tag) := true.B }
    }
  }

  io.busy := saturn.io.busy || special.io.busy || hblInFlight.asUInt.orR || pendingReleases.orR
}
