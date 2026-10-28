package xiangshan.evu

import chisel3._
import chisel3.util._
import hardfloat.{INToRecFN, MulAddRecFN, fNFromRecFN, recFNFromFN}

class EvuDequantResult(p: EvuParams) extends Bundle {
  val beat = new MatrixMicroBlock(p)
  val fflags = UInt(5.W)
}

/** CUTE [N,M] INT32 -> FP32 -> rounded scaleA[M] -> rounded scaleB[N].
  * Cache only the current DLEN chunk of each scale vector. The input queue
  * owns a miss until both reads complete, even if another command arrives.
  */
class EvuMatrixDequant(p: EvuParams) extends Module {
  private val elements = p.dlenBits / 32
  private val elementBits = log2Ceil(elements)
  private val chunkBits = log2Ceil(p.vlenBits / p.dlenBits)
  val io = IO(new Bundle {
    val matrix = new EvuMatrixSpecialIO(p)
    val read = Vec(2, Decoupled(new EvuSpecialReadReq(p)))
    val readData = Vec(2, Input(UInt(p.dlenBits.W)))
    val startAllowed = Input(Bool())
    val invalidate = Input(Bool())
    val flush = Input(Bool())
    val active = Output(Bool())
    val busy = Output(Bool())
  })
  val queue = Module(new Queue(new MatrixMicroBlock(p), 2, pipe = true, hasFlush = true))
  queue.io.flush.get := io.flush
  queue.io.enq.valid := io.matrix.in.valid && !io.flush
  queue.io.enq.bits := io.matrix.in.bits
  io.matrix.in.ready := queue.io.enq.ready && !io.flush
  val reading = RegInit(false.B)
  val beat = queue.io.deq.bits
  val cacheValid = RegInit(VecInit.fill(2)(false.B))
  val cacheAddr = Reg(Vec(2, UInt(p.regIndexWidth.W)))
  val cacheChunk = Reg(Vec(2, UInt(chunkBits.W)))
  val cacheData = Reg(Vec(2, UInt(p.dlenBits.W)))
  val tileDim = Mux(beat.tileDim === 0.U, 8.U, beat.tileDim)
  val row = Mux(beat.tileDim === 0.U, beat.microblockId, beat.row)
  val col = Mux(beat.tileDim === 0.U, 0.U, beat.col)
  val globalM = beat.tileId(3, 0) * tileDim + col
  val globalN = beat.tileId(11, 4) * tileDim + row
  val ownsHead = queue.io.deq.valid && (reading || io.startAllowed) && !io.flush
  val hits = Wire(Vec(2, Bool()))
  for (i <- 0 until 2) {
    io.read(i).bits.addr := (if (i == 0) beat.scaleTag else beat.biasTag)(p.regIndexWidth - 1, 0)
    io.read(i).bits.chunk := (if (i == 0) globalM else globalN) >> elementBits
    io.read(i).bits.oldest := true.B
    hits(i) := cacheValid(i) && !io.invalidate && cacheAddr(i) === io.read(i).bits.addr &&
      cacheChunk(i) === io.read(i).bits.chunk
    // Independent collection is required when both operands select one bank.
    io.read(i).valid := ownsHead && !hits(i)
    when(io.read(i).fire) {
      cacheValid(i) := true.B
      cacheAddr(i) := io.read(i).bits.addr
      cacheChunk(i) := io.read(i).bits.chunk
      cacheData(i) := io.readData(i)
    }
  }

  class Scaled extends Bundle {
    val result = new EvuDequantResult(p)
    val scaleB = UInt(32.W)
  }
  val first = Module(new EvuElasticStage(new Scaled))
  val second = Module(new EvuElasticStage(new EvuDequantResult(p)))
  val output = Module(new Queue(new EvuDequantResult(p), 2, pipe = true, hasFlush = true))
  first.io.flush := io.flush
  second.io.flush := io.flush
  output.io.flush.get := io.flush
  first.io.in.valid := ownsHead && hits.asUInt.andR
  queue.io.deq.ready := (reading || io.startAllowed) && hits.asUInt.andR && first.io.in.ready && !io.flush
  first.io.in.bits.result.beat := beat
  val scaleA = (cacheData(0) >> (globalM(elementBits - 1, 0) << 5)).asTypeOf(Vec(elements, UInt(32.W)))
  val scaleB = cacheData(1).asTypeOf(Vec(elements, UInt(32.W)))(globalN(elementBits - 1, 0))
  first.io.in.bits.scaleB := scaleB
  val ints = beat.data.asTypeOf(Vec(elements, UInt(32.W)))
  val firstData = Wire(Vec(elements, UInt(32.W)))
  val firstFlags = Wire(Vec(elements, UInt(5.W)))
  for (i <- 0 until elements) {
    val convert = Module(new INToRecFN(32, 8, 24))
    convert.io.in := ints(i)
    convert.io.signedIn := true.B
    convert.io.roundingMode := 0.U
    convert.io.detectTininess := 1.U
    val mul = Module(new MulAddRecFN(8, 24))
    mul.io.op := 0.U
    mul.io.a := convert.io.out
    mul.io.b := recFNFromFN(8, 24, scaleA(i))
    mul.io.c := recFNFromFN(8, 24, 0.U(32.W))
    mul.io.roundingMode := 0.U
    mul.io.detectTininess := 1.U
    firstData(i) := fNFromRecFN(8, 24, mul.io.out)
    firstFlags(i) := Mux(beat.validLanes(i), convert.io.exceptionFlags | mul.io.exceptionFlags, 0.U)
  }
  first.io.in.bits.result.beat.data := firstData.asUInt
  first.io.in.bits.result.fflags := firstFlags.reduce(_ | _)
  second.io.in.valid := first.io.out.valid
  first.io.out.ready := second.io.in.ready
  second.io.in.bits := first.io.out.bits.result
  val intermediate = first.io.out.bits.result.beat.data.asTypeOf(Vec(elements, UInt(32.W)))
  val finalData = Wire(Vec(elements, UInt(32.W)))
  val finalFlags = Wire(Vec(elements, UInt(5.W)))
  for (i <- 0 until elements) {
    val mul = Module(new MulAddRecFN(8, 24))
    mul.io.op := 0.U
    mul.io.a := recFNFromFN(8, 24, intermediate(i))
    mul.io.b := recFNFromFN(8, 24, first.io.out.bits.scaleB)
    mul.io.c := recFNFromFN(8, 24, 0.U(32.W))
    mul.io.roundingMode := 0.U
    mul.io.detectTininess := 1.U
    val valid = first.io.out.bits.result.beat.validLanes(i)
    finalData(i) := Mux(valid, fNFromRecFN(8, 24, mul.io.out), 0.U)
    finalFlags(i) := Mux(valid, mul.io.exceptionFlags, 0.U)
  }
  second.io.in.bits.beat.data := finalData.asUInt
  second.io.in.bits.fflags := first.io.out.bits.result.fflags | finalFlags.reduce(_ | _)
  output.io.enq <> second.io.out
  io.matrix.out.valid := output.io.deq.valid && !io.flush
  io.matrix.out.bits := output.io.deq.bits.beat
  io.matrix.fflags := output.io.deq.bits.fflags
  output.io.deq.ready := io.matrix.out.ready && !io.flush

  when(ownsHead) { reading := !queue.io.deq.fire }
  io.active := reading || ownsHead || first.io.out.valid || second.io.out.valid || output.io.deq.valid
  io.busy := io.active || queue.io.deq.valid
  when(io.invalidate || io.flush) { cacheValid.foreach(_ := false.B) }
  when(io.flush) { reading := false.B }
}
