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

/** Queued rows share one quant pipeline; beat metadata survives row changes.
  * Matrix dequantization uses the same VRF requests after quant work drains.
  */
class EvuSpecialSequencer(p: EvuParams) extends Module {
  private val chunkBits = log2Ceil(p.vlenBits / p.dlenBits)
  private val elements = p.dlenBits / 32
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new EvuCommand(p)))
    val completion = Decoupled(new EvuCompletion(p))
    val flush = Input(Bool())
    // Any accepted ordinary VRF write invalidates the tiny scale caches.
    val invalidate = Input(Bool())
    val busy = Output(Bool())
    val vrf = new EvuSpecialVrfIO(p)
    val cuteWrite = Decoupled(new EvuCuteWrite(p))
    val matrix = new EvuMatrixSpecialIO(p)
  })
  val active = RegInit(false.B)
  val cmd = Reg(new EvuCommand(p))
  val commands = Module(new Queue(new EvuCommand(p), p.quantCommandDepth, pipe = true, hasFlush = true))
  val completions = Module(new Queue(new EvuCompletion(p), p.quantCommandDepth, pipe = true, hasFlush = true))
  class QuantBeat extends Bundle {
    val target = UInt(9.W)
    val last = Bool()
    val completion = new EvuCompletion(p)
  }
  val metadata = Module(new Queue(new QuantBeat, 8, pipe = true, hasFlush = true))
  Seq(commands.io.flush.get, completions.io.flush.get, metadata.io.flush.get).foreach(_ := io.flush)
  val flags = RegInit(VecInit.fill(p.commandDepth)(0.U(5.W)))
  val readChunk = RegInit(VecInit.fill(2)(0.U((chunkBits + 1).W)))
  val issueChunk = RegInit(0.U((chunkBits + 1).W))
  val totalChunks = (cmd.vl +& (elements - 1).U) / elements.U
  val scalarScale = cmd.aux(4)
  val scalarScaleData = Reg(UInt(32.W))
  val scalarScaleValid = RegInit(false.B)
  val operands = Seq.fill(2)(Module(new Queue(UInt(p.dlenBits.W), 2, pipe = true, hasFlush = true)))
  val quant = Module(new EvuFpQuant(p))
  quant.io.flush := io.flush
  val matrix = Module(new EvuMatrixDequant(p))
  matrix.io.matrix <> io.matrix
  matrix.io.flush := io.flush
  matrix.io.invalidate := io.invalidate
  commands.io.enq.valid := io.command.valid && !matrix.io.active && !io.flush
  commands.io.enq.bits := io.command.bits
  io.command.ready := commands.io.enq.ready && !matrix.io.active && !io.flush
  matrix.io.startAllowed := !active && !commands.io.deq.valid && !metadata.io.deq.valid &&
    !completions.io.deq.valid && !io.command.valid
  io.completion <> completions.io.deq
  when(io.flush) { io.completion.valid := false.B }
  def resultFor(command: EvuCommand): EvuCompletion = {
    val result = WireDefault(0.U.asTypeOf(new EvuCompletion(p)))
    result.pc := command.pc
    result.opcode := command.opcode
    result.vd := command.vd
    result.jobId := command.jobId
    result.epoch := command.epoch
    result
  }

  for (i <- 0 until 3) {
    io.vrf.read(i).valid := false.B
    io.vrf.read(i).bits := 0.U.asTypeOf(new EvuSpecialReadReq(p))
    io.vrf.read(i).bits.oldest := true.B
  }
  for (i <- 0 until 2) {
    val q = operands(i)
    q.io.flush.get := io.flush
    io.vrf.read(i).valid := active && readChunk(i) < totalChunks && q.io.enq.ready && !io.flush &&
      (if (i == 1) !scalarScale || !scalarScaleValid else true.B)
    io.vrf.read(i).bits.addr := (if (i == 0) cmd.vs2 else cmd.vd)
    io.vrf.read(i).bits.chunk := readChunk(i)(chunkBits - 1, 0)
    q.io.enq.valid := io.vrf.read(i).fire && !matrix.io.active && (if (i == 1) !scalarScale else true.B)
    q.io.enq.bits := io.vrf.readData(i)
    when(q.io.enq.fire) { readChunk(i) := readChunk(i) + 1.U }
    if (i == 1) {
      when(io.vrf.read(i).fire && active && scalarScale && !matrix.io.active) {
        scalarScaleData := io.vrf.readData(i)(31, 0)
        scalarScaleValid := true.B
      }
    }
  }
  // Port 1 is shared by quant operand 1 and matrix operand 0. Apply matrix
  // ownership after both quant defaults so the second default cannot mask it.
  for (i <- 0 until 2) {
    matrix.io.read(i).ready := io.vrf.read(i + 1).ready && matrix.io.active
    matrix.io.readData(i) := io.vrf.readData(i + 1)
    when(matrix.io.active) {
      io.vrf.read(i + 1).valid := matrix.io.read(i).valid
      io.vrf.read(i + 1).bits := matrix.io.read(i).bits
    }
  }
  val scaleReady = Mux(scalarScale, scalarScaleValid, operands(1).io.deq.valid)
  quant.io.in.valid := active && operands(0).io.deq.valid && scaleReady && metadata.io.enq.ready && !io.flush
  quant.io.in.bits.input := operands(0).io.deq.bits
  quant.io.in.bits.scale := Mux(scalarScale, Fill(elements, scalarScaleData), operands(1).io.deq.bits)
  quant.io.in.bits.chunk := issueChunk(chunkBits - 1, 0)
  quant.io.in.bits.validLanes := VecInit((0 until elements).map(i =>
    issueChunk * elements.U + i.U < cmd.vl)).asUInt
  operands(0).io.deq.ready := quant.io.in.ready && active && scaleReady && metadata.io.enq.ready && !io.flush
  operands(1).io.deq.ready := quant.io.in.ready && active && operands(0).io.deq.valid &&
    metadata.io.enq.ready && !io.flush && !scalarScale
  metadata.io.enq.valid := quant.io.in.fire
  metadata.io.enq.bits.target := cmd.base(8, 0)
  metadata.io.enq.bits.last := issueChunk +& 1.U === totalChunks
  metadata.io.enq.bits.completion := resultFor(cmd)
  when(quant.io.in.fire) {
    issueChunk := issueChunk + 1.U
    when(metadata.io.enq.bits.last) { active := false.B }
  }

  val result = quant.io.out.bits
  val beat = metadata.io.deq.bits
  val outputAllowed = metadata.io.deq.valid && (!beat.last || completions.io.enq.ready)
  io.cuteWrite.valid := quant.io.out.valid && outputAllowed && !io.flush
  quant.io.out.ready := io.cuteWrite.ready && outputAllowed && !io.flush
  metadata.io.deq.ready := io.cuteWrite.fire
  io.cuteWrite.bits := 0.U.asTypeOf(new EvuCuteWrite(p))
  if (p.dlenBits == 256) {
    val row = beat.target(8, 2)
    val offset = result.chunk.pad(2)(1, 0) << 3
    io.cuteWrite.bits.matrixReg := beat.target(1, 0)
    io.cuteWrite.bits.bank := row(2, 0)
    io.cuteWrite.bits.entry := (row >> 3) * 2.U + (result.chunk >> 2)
    io.cuteWrite.bits.byteMask := (result.validLanes.pad(32) << offset)(31, 0)
    io.cuteWrite.bits.data := (result.data.pad(256) << (offset << 3))(255, 0)
  }
  val incoming = commands.io.deq.bits
  val rejected = incoming.opcode =/= EvuOpcode.FpQuantToCute ||
    !EvuIsa.supportsElementWidth(incoming.opcode, incoming.sew) ||
    incoming.base(63, 9).orR || incoming.vl > (64 min (p.vlenBits / 32)).U ||
    (p.dlenBits != 256).B
  val empty = incoming.vl === 0.U
  val immediate = rejected || empty
  val lastWrite = io.cuteWrite.fire && beat.last
  commands.io.deq.ready := !active && !matrix.io.active && !io.flush &&
    (!immediate || (completions.io.enq.ready && !lastWrite))
  completions.io.enq.valid := lastWrite || (commands.io.deq.fire && immediate)
  completions.io.enq.bits := Mux(lastWrite, beat.completion, resultFor(incoming))
  completions.io.enq.bits.fflags := Mux(lastWrite, flags(beat.completion.jobId) | result.fflags, 0.U)
  completions.io.enq.bits.exception := !lastWrite && rejected
  completions.io.enq.bits.cause := Mux(!lastWrite && rejected, EvuFault.Illegal, EvuFault.None)
  io.busy := active || commands.io.deq.valid || metadata.io.deq.valid ||
    completions.io.deq.valid || matrix.io.busy || quant.io.busy

  when(commands.io.deq.fire) {
    cmd := incoming
    flags(incoming.jobId) := 0.U
    scalarScaleValid := false.B
    issueChunk := 0.U
    readChunk.foreach(_ := 0.U)
    active := !rejected && !empty
  }
  when(io.cuteWrite.fire) {
    flags(beat.completion.jobId) := flags(beat.completion.jobId) | result.fflags
  }
  when(io.flush) {
    active := false.B
    flags.foreach(_ := 0.U)
    scalarScaleValid := false.B
    issueChunk := 0.U
    readChunk.foreach(_ := 0.U)
  }
}
