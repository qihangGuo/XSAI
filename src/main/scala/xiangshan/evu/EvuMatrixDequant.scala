package xiangshan.evu

import chisel3._
import chisel3.util._

/** CUTE's [N,M] INT32 stream with scaleA[M] and scaleB[N] in Saturn's VRF. */
class EvuMatrixDequant(p: EvuParams) extends Module {
  private val elements = p.dlenBits / 32
  private val elementBits = log2Ceil(elements)
  val io = IO(new Bundle {
    val matrix = new EvuMatrixSpecialIO(p)
    val read = Vec(2, Decoupled(new EvuSpecialReadReq(p)))
    val readData = Vec(2, Input(UInt(p.dlenBits.W)))
    val startAllowed = Input(Bool())
    val flush = Input(Bool())
    val active = Output(Bool())
    val busy = Output(Bool())
  })

  val queue = Module(new Queue(new MatrixMicroBlock(p), 2, hasFlush = true))
  queue.io.flush.get := io.flush
  queue.io.enq.valid := io.matrix.in.valid && !io.flush
  queue.io.enq.bits := io.matrix.in.bits
  io.matrix.in.ready := queue.io.enq.ready && !io.flush
  val reading = RegInit(false.B)
  val pending = RegInit(false.B)
  val haveScale = RegInit(false.B)
  val haveBias = RegInit(false.B)
  val scale = Reg(UInt(p.dlenBits.W))
  val bias = Reg(UInt(p.dlenBits.W))
  val beat = Reg(new MatrixMicroBlock(p))
  val flags = RegInit(0.U(5.W))

  val tileDim = Mux(beat.tileDim === 0.U, 8.U, beat.tileDim)
  val row = Mux(beat.tileDim === 0.U, beat.microblockId, beat.row)
  val col = Mux(beat.tileDim === 0.U, 0.U, beat.col)
  val globalM = beat.tileId(3, 0) * tileDim + col
  val globalN = beat.tileId(11, 4) * tileDim + row
  io.read(0).bits.addr := beat.scaleTag(p.regIndexWidth - 1, 0)
  io.read(0).bits.chunk := globalM >> elementBits
  io.read(1).bits.addr := beat.biasTag(p.regIndexWidth - 1, 0)
  io.read(1).bits.chunk := globalN >> elementBits
  io.read.foreach(_.bits.oldest := true.B)
  // Collect operands separately: both requests may select the same VRF bank.
  io.read(0).valid := reading && !haveScale && !io.flush
  io.read(1).valid := reading && haveScale && !haveBias && !io.flush
  when(io.read(0).fire) { scale := io.readData(0); haveScale := true.B }
  when(io.read(1).fire) { bias := io.readData(1); haveBias := true.B }

  val quant = Module(new EvuQuant(p))
  quant.io.in.valid := reading && haveScale && haveBias && !io.flush
  quant.io.in.bits := beat.data
  quant.io.scale := scale >> (globalM(elementBits - 1, 0) << 5)
  quant.io.bias := bias
  quant.io.zero := 0.S
  quant.io.shift := 0.U
  quant.io.sew := 2.U
  quant.io.op := EvuQuantOp.Dequant
  quant.io.floating := true.B
  quant.io.scaleProduct := true.B
  quant.io.foldLog2e := false.B
  quant.io.broadcastIndex := globalN(elementBits - 1, 0)
  quant.io.validLanes := Fill(p.dlenBits / 32, 1.U(1.W))
  quant.io.out.ready := !pending && !io.flush

  queue.io.deq.ready := !reading && !pending && io.startAllowed && !io.flush
  when(queue.io.deq.fire) {
    beat := queue.io.deq.bits
    reading := true.B
    haveScale := false.B
    haveBias := false.B
  }
  when(quant.io.out.fire) {
    beat.data := quant.io.out.bits
    flags := quant.io.fflags
    reading := false.B
    pending := true.B
  }
  io.matrix.out.valid := pending && !io.flush
  io.matrix.out.bits := beat
  io.matrix.fflags := flags
  when(io.matrix.out.fire) { pending := false.B }
  io.active := reading || pending
  io.busy := io.active || queue.io.deq.valid
  when(io.flush) {
    reading := false.B
    pending := false.B
    haveScale := false.B
    haveBias := false.B
    flags := 0.U
  }
}
