package xiangshan.evu

import chisel3._
import chisel3.util._

class EvuSpecialReadReq(p: EvuParams) extends Bundle {
  val addr = UInt(p.regIndexWidth.W)
  val chunk = UInt(log2Ceil(p.vlenBits / p.dlenBits).W)
  val oldest = Bool()
}

class EvuSpecialVrfIO(p: EvuParams) extends Bundle {
  val read = Vec(3, Decoupled(new EvuSpecialReadReq(p)))
  val readData = Vec(3, Input(UInt(p.dlenBits.W)))
}

class EvuMatrixSpecialIO(p: EvuParams) extends Bundle {
  val in = Flipped(Decoupled(new MatrixMicroBlock(p)))
  val out = Decoupled(new MatrixMicroBlock(p))
  val fflags = Output(UInt(5.W))
}

/**
  * Saturn-managed AI special sequencer.
  *
  * It reads Saturn's VRF to quantize CUTE inputs and separately processes
  * CUTE's result stream. Neither path writes the Saturn VRF.
  */
class EvuSpecialSequencer(p: EvuParams) extends Module {
  private val chunkBits = log2Ceil(p.vlenBits / p.dlenBits)
  private val descCount = 8
  private val descBits = log2Ceil(descCount)
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new EvuCommand(p)))
    val completion = Decoupled(new EvuCompletion(p))
    val flush = Input(Bool())
    val busy = Output(Bool())
    val vrf = new EvuSpecialVrfIO(p)
    val cuteWrite = Decoupled(new EvuCuteWrite(p))
    val matrix = new EvuMatrixSpecialIO(p)
  })

  val descriptors = Reg(Vec(descCount, new EvuDescriptor(p)))
  val active = RegInit(false.B)
  val readIssued = RegInit(false.B)
  val resultPending = RegInit(false.B)
  val completionPending = RegInit(false.B)
  val cmdReg = Reg(new EvuCommand(p))
  val completionReg = RegInit(0.U.asTypeOf(new EvuCompletion(p)))
  val chunk = RegInit(0.U(chunkBits.W))
  val resultData = Reg(UInt(p.dlenBits.W))
  val resultSat = RegInit(false.B)
  val readCaptured = RegInit(VecInit.fill(3)(false.B))
  val readDataReg = Reg(Vec(3, UInt(p.dlenBits.W)))
  val matrix = Module(new EvuMatrixDequant(p))
  matrix.io.matrix <> io.matrix
  matrix.io.flush := io.flush
  matrix.io.startAllowed := !active && !completionPending && !io.command.valid

  val cfgIndex = cmdReg.aux(descBits - 1, 0)
  val descriptor = WireDefault(descriptors(cfgIndex))
  when(cmdReg.config.valid) {
    descriptor.scaleReg := cmdReg.descriptor(4, 0)
    descriptor.biasReg := cmdReg.descriptor(9, 5)
    descriptor.zero := cmdReg.descriptor(41, 10).asSInt
    descriptor.shift := cmdReg.descriptor(47, 42)
    descriptor.outputSew := cmdReg.descriptor(49, 48)
    descriptor.valid := cmdReg.descriptor(50)
  }
  val isQuant = active && cmdReg.opcode === EvuOpcode.QuantToCute
  val activeBytes = cmdReg.vl << cmdReg.sew
  val chunkBase = chunk * p.dlenBytes.U
  val lastChunk = chunkBase +& p.dlenBytes.U >= activeBytes
  // The current quantizer emits 32-bit words; SEW controls the command's byte extent.
  val byteMask = VecInit((0 until p.dlenBytes).map(i => chunkBase +& i.U < activeBytes)).asUInt

  for (i <- 0 until 3) {
    io.vrf.read(i).valid := false.B
    io.vrf.read(i).bits.addr := 0.U
    io.vrf.read(i).bits.chunk := chunk
    io.vrf.read(i).bits.oldest := true.B
  }
  val commandRead = isQuant && !readIssued && !resultPending && !io.flush
  // Valid must not depend on ready.  The Saturn RegisterFile arbitrates
  // several requesters and its ready may depend on the request-valid vector;
  // gating read(0).valid with read(0).ready creates a FIRRTL combinational
  // cycle through the special VRF crossbar.
  val read0Active = commandRead && !readCaptured(0)
  val read12Active = isQuant && commandRead && !io.flush
  io.vrf.read(0).valid := read0Active
  io.vrf.read(0).bits.addr := cmdReg.vs2
  io.vrf.read(1).valid := read12Active && !readCaptured(1)
  io.vrf.read(1).bits.addr := descriptor.scaleReg
  io.vrf.read(2).valid := read12Active && !readCaptured(2)
  io.vrf.read(2).bits.addr := descriptor.biasReg
  for (i <- 0 until 2) {
    matrix.io.read(i).ready := io.vrf.read(i + 1).ready && matrix.io.active
    matrix.io.readData(i) := io.vrf.readData(i + 1)
    when(matrix.io.active) { io.vrf.read(i + 1) <> matrix.io.read(i) }
  }
  val specialRead0Fire = io.vrf.read(0).fire && !matrix.io.active
  val specialRead1Fire = io.vrf.read(1).fire && !matrix.io.active
  val specialRead2Fire = io.vrf.read(2).fire && !matrix.io.active
  when (specialRead0Fire) {
    readDataReg(0) := io.vrf.readData(0)
    readCaptured(0) := true.B
  }
  when (specialRead1Fire) {
    readDataReg(1) := io.vrf.readData(1)
    readCaptured(1) := true.B
  }
  when (specialRead2Fire) {
    readDataReg(2) := io.vrf.readData(2)
    readCaptured(2) := true.B
  }
  val operandsCaptured = readCaptured.asUInt.andR
  val operandIssue = active && !readIssued && !resultPending && operandsCaptured && !io.flush

  val quant = Module(new EvuQuant(p))
  quant.io.in.valid := operandIssue && isQuant
  quant.io.in.bits := readDataReg(0)
  quant.io.scale := readDataReg(1)
  quant.io.bias := readDataReg(2)
  quant.io.zero := descriptor.zero
  quant.io.shift := descriptor.shift
  quant.io.sew := descriptor.outputSew
  quant.io.op := EvuQuantOp.Requant
  quant.io.floating := false.B
  quant.io.scaleProduct := false.B
  quant.io.foldLog2e := false.B
  quant.io.broadcastIndex := 0.U
  quant.io.validLanes := VecInit((0 until p.dlenBits / 32).map(i => byteMask(4 * i))).asUInt

  quant.io.out.ready := active && isQuant && !resultPending && !io.flush

  io.cuteWrite.valid := resultPending && !io.flush
  io.cuteWrite.bits.matrixReg := 0.U
  io.cuteWrite.bits.bank := 0.U
  io.cuteWrite.bits.entry := chunk
  io.cuteWrite.bits.data := resultData
  io.cuteWrite.bits.byteMask := byteMask
  io.command.ready := !active && !completionPending && !matrix.io.active && !io.flush
  io.completion.valid := completionPending && !io.flush
  io.completion.bits := completionReg
  io.busy := active || completionPending || matrix.io.busy || quant.io.out.valid

  when (io.command.fire) {
    completionReg.pc := io.command.bits.pc
    completionReg.tval := 0.U
    completionReg.tvalValid := false.B
    when (io.command.bits.opcode === EvuOpcode.SetCfg &&
      EvuIsa.supportsElementWidth(io.command.bits.opcode, io.command.bits.sew)) {
      val idx = io.command.bits.vd(descBits - 1, 0)
      descriptors(idx).scaleReg := io.command.bits.vs1
      descriptors(idx).biasReg := io.command.bits.vs2
      descriptors(idx).zero := io.command.bits.base(31, 0).asSInt
      descriptors(idx).shift := io.command.bits.aux(5, 0)
      descriptors(idx).outputSew := io.command.bits.sew(1, 0)
      descriptors(idx).valid := true.B
      completionReg.opcode := io.command.bits.opcode
      completionReg.vd := io.command.bits.vd
      completionReg.exception := false.B
      completionReg.fflags := 0.U
      completionReg.vxsat := false.B
      completionReg.dirtyVs := false.B
      completionReg.cause := EvuFault.None
      completionReg.jobId := io.command.bits.jobId
      completionReg.epoch := io.command.bits.epoch
      completionPending := true.B
    }.otherwise {
      cmdReg := io.command.bits
      readCaptured := VecInit.fill(3)(false.B)
      val rejected = io.command.bits.opcode =/= EvuOpcode.QuantToCute ||
        !EvuIsa.supportsElementWidth(io.command.bits.opcode, io.command.bits.sew) ||
        (io.command.bits.config.valid && !io.command.bits.descriptor(50))
      val empty = io.command.bits.vl === 0.U
      active := !rejected && !empty
      readIssued := false.B
      resultPending := false.B
      chunk := 0.U
      resultSat := false.B
      when (rejected || empty) {
        completionReg.opcode := io.command.bits.opcode
        completionReg.vd := io.command.bits.vd
        completionReg.exception := rejected
        completionReg.fflags := 0.U
        completionReg.vxsat := false.B
        completionReg.dirtyVs := false.B
        completionReg.cause := Mux(rejected, EvuFault.Illegal, EvuFault.None)
        completionReg.jobId := io.command.bits.jobId
        completionReg.epoch := io.command.bits.epoch
        completionPending := true.B
      }
    }
  }
  when (quant.io.in.fire) { readIssued := true.B }
  when (quant.io.out.fire) {
    resultPending := true.B
    readIssued := false.B
    readCaptured := VecInit.fill(3)(false.B)
    resultData := quant.io.out.bits
    resultSat := resultSat || quant.io.saturated
  }
  when (io.cuteWrite.fire) {
    resultPending := false.B
    when (lastChunk) {
      active := false.B
      completionPending := true.B
      completionReg.opcode := cmdReg.opcode
      completionReg.vd := cmdReg.vd
      completionReg.exception := false.B
      completionReg.fflags := 0.U
      completionReg.vxsat := resultSat
      completionReg.dirtyVs := false.B
      completionReg.cause := EvuFault.None
      completionReg.jobId := cmdReg.jobId
      completionReg.epoch := cmdReg.epoch
    }.otherwise {
      chunk := chunk + 1.U
    }
  }
  when (io.completion.fire) { completionPending := false.B }
  when (io.flush) {
    active := false.B
    readIssued := false.B
    resultPending := false.B
    completionPending := false.B
    readCaptured := VecInit.fill(3)(false.B)
    for (i <- 0 until descCount) { descriptors(i).valid := false.B }
  }
}
