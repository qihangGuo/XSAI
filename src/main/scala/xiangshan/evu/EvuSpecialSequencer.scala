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

/** FP32 probability packing and CUTE result dequantization share Saturn's VRF.
  * Two slots per quant operand overlap independent VRF reads with arithmetic
  * and accepted CUTE writes. Neither path writes the Saturn VRF.
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
  val completionPending = RegInit(false.B)
  val cmd = Reg(new EvuCommand(p))
  val completion = RegInit(0.U.asTypeOf(new EvuCompletion(p)))
  val flags = RegInit(0.U(5.W))
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
  matrix.io.startAllowed := !active && !completionPending && !io.command.valid

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
  quant.io.in.valid := active && operands(0).io.deq.valid && scaleReady && !io.flush
  quant.io.in.bits.input := operands(0).io.deq.bits
  quant.io.in.bits.scale := Mux(scalarScale, Fill(elements, scalarScaleData), operands(1).io.deq.bits)
  quant.io.in.bits.chunk := issueChunk(chunkBits - 1, 0)
  quant.io.in.bits.validLanes := VecInit((0 until elements).map(i =>
    issueChunk * elements.U + i.U < cmd.vl)).asUInt
  operands(0).io.deq.ready := quant.io.in.ready && active && scaleReady && !io.flush
  operands(1).io.deq.ready := quant.io.in.ready && active && operands(0).io.deq.valid && !io.flush && !scalarScale
  when(quant.io.in.fire) { issueChunk := issueChunk + 1.U }

  val result = quant.io.out.bits
  io.cuteWrite.valid := active && quant.io.out.valid && !io.flush
  quant.io.out.ready := active && io.cuteWrite.ready && !io.flush
  io.cuteWrite.bits := 0.U.asTypeOf(new EvuCuteWrite(p))
  if (p.dlenBits == 256) {
    val row = cmd.base(8, 2)
    val offset = result.chunk.pad(2)(1, 0) << 3
    io.cuteWrite.bits.matrixReg := cmd.base(1, 0)
    io.cuteWrite.bits.bank := row(2, 0)
    io.cuteWrite.bits.entry := (row >> 3) * 2.U + (result.chunk >> 2)
    io.cuteWrite.bits.byteMask := (result.validLanes.pad(32) << offset)(31, 0)
    io.cuteWrite.bits.data := (result.data.pad(256) << (offset << 3))(255, 0)
  }
  io.command.ready := !active && !completionPending && !matrix.io.active && !io.flush
  io.completion.valid := completionPending && !io.flush
  io.completion.bits := completion
  io.busy := active || completionPending || matrix.io.busy || quant.io.busy

  when(io.command.fire) {
    cmd := io.command.bits
    flags := 0.U
    scalarScaleValid := false.B
    issueChunk := 0.U
    readChunk.foreach(_ := 0.U)
    completion := 0.U.asTypeOf(new EvuCompletion(p))
    completion.pc := io.command.bits.pc
    completion.opcode := io.command.bits.opcode
    completion.vd := io.command.bits.vd
    completion.jobId := io.command.bits.jobId
    completion.epoch := io.command.bits.epoch
    val rejected = io.command.bits.opcode =/= EvuOpcode.FpQuantToCute ||
      !EvuIsa.supportsElementWidth(io.command.bits.opcode, io.command.bits.sew) ||
      io.command.bits.base(63, 9).orR || io.command.bits.vl > (64 min (p.vlenBits / 32)).U ||
      (p.dlenBits != 256).B
    val empty = io.command.bits.vl === 0.U
    active := !rejected && !empty
    completionPending := rejected || empty
    completion.exception := rejected
    completion.cause := Mux(rejected, EvuFault.Illegal, EvuFault.None)
  }
  when(io.cuteWrite.fire) {
    flags := flags | result.fflags
    // Completion includes this final accepted write's flags, not just older beats.
    when((result.chunk +& 1.U) === totalChunks) {
      active := false.B
      completionPending := true.B
      completion.fflags := flags | result.fflags
    }
  }
  when(io.completion.fire) { completionPending := false.B }
  when(io.flush) {
    active := false.B
    completionPending := false.B
    flags := 0.U
    scalarScaleValid := false.B
    issueChunk := 0.U
    readChunk.foreach(_ := 0.U)
  }
}
