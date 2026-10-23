package xiangshan.evu

import chisel3._
import chisel3.util._

class MatrixMicroBlock(p: EvuParams) extends Bundle {
  val data = UInt(p.dlenBits.W)
  val tileId = UInt(16.W)
  val microblockId = UInt(8.W)
  val sew = UInt(3.W)
  val kComplete = Bool()
  val taskLast = Bool()
  val scaleTag = UInt(16.W)
  val biasTag = UInt(16.W)
  val row = UInt(8.W)
  val col = UInt(8.W)
  val tileDim = UInt(8.W)
  val validLanes = UInt((p.dlenBits / 32).W)
  val microblockBeat = UInt(8.W)
  val microblockBeats = UInt(8.W)
}

class MatrixTileResult(tileBits: Int) extends Bundle {
  val data = UInt(tileBits.W)
  val tileId = UInt(16.W)
  val kComplete = Bool()
  val taskLast = Bool()
  val scaleTag = UInt(16.W)
  val biasTag = UInt(16.W)
}

class MatrixVectorBridgeIO(tileBits: Int, p: EvuParams) extends Bundle {
  val in = Flipped(Decoupled(new MatrixTileResult(tileBits)))
  val out = Decoupled(new MatrixMicroBlock(p))
}

/** Serializes one completed CUTE tile into row-local DLEN beats. */
class MatrixVectorBridge(tileBits: Int, p: EvuParams) extends Module {
  require(tileBits > 0 && tileBits % 32 == 0)
  private val tileElements = tileBits / 32
  private val tileDim = math.sqrt(tileElements.toDouble).toInt
  require(tileDim * tileDim == tileElements, s"CUTE result must be a square INT32 tile, got ${tileBits} bits")
  private val elemsPerBeat = p.dlenBits / 32
  require(elemsPerBeat > 0)
  private val beatsPerRow = (tileDim + elemsPerBeat - 1) / elemsPerBeat
  private val microDim = if (tileDim >= 8) 8 else tileDim
  private val microCols = (tileDim + microDim - 1) / microDim
  private val beatsPerMicroRow = (microDim + elemsPerBeat - 1) / elemsPerBeat
  private val totalBeats = tileDim * beatsPerRow
  private val beatBits = log2Ceil(totalBeats max 2)
  val io = IO(new MatrixVectorBridgeIO(tileBits, p))

  val active = RegInit(false.B)
  private val tileQueue = Module(new Queue(new MatrixTileResult(tileBits), 2))
  // Partial-K snapshots are consumed at this boundary but never enqueued.
  // Keep input ready independent of input bits: CUTE derives kComplete from
  // the result path, and making ready depend on it would create a
  // ready -> kComplete -> ready combinational cycle. A partial result may be
  // backpressured when the FIFO is full, which is safe for CUTE's reduction.
  tileQueue.io.enq.valid := io.in.valid && io.in.bits.kComplete
  tileQueue.io.enq.bits := io.in.bits
  io.in.ready := tileQueue.io.enq.ready
  val tile = Reg(UInt(tileBits.W))
  val tileId = Reg(UInt(16.W))
  val kComplete = RegInit(false.B)
  val taskLast = RegInit(false.B)
  val scaleTag = Reg(UInt(16.W))
  val biasTag = Reg(UInt(16.W))
  val beat = RegInit(0.U(beatBits.W))
  val tileElems = tile.asTypeOf(Vec(tileElements, UInt(32.W)))

  val row = beat / beatsPerRow.U
  val col = (beat % beatsPerRow.U) * elemsPerBeat.U
  val microblockRow = row / microDim.U
  val microblockCol = col / microDim.U
  val microblockId = microblockRow * microCols.U + microblockCol
  val microblockBeat = (row % microDim.U) * beatsPerMicroRow.U +
    ((col % microDim.U) / elemsPerBeat.U)
  val valid = Wire(Vec(elemsPerBeat, Bool()))
  val data = Wire(Vec(elemsPerBeat, UInt(32.W)))
  for (lane <- 0 until elemsPerBeat) {
    val element = row * tileDim.U + col + lane.U
    valid(lane) := element < tileElements.U && col + lane.U < tileDim.U
    val elementIndex = element(log2Ceil(tileElements max 2) - 1, 0)
    data(lane) := Mux(valid(lane), tileElems(elementIndex), 0.U)
  }

  // The queue is allowed to fill while the current tile is being emitted.
  // kComplete is the CUTE release token: a partial-K result is not allowed
  // to acquire a bridge slot, so it can never be mistaken for a final C.
  // On the final beat, a queued tile can take ownership in the same cycle,
  // eliminating the bubble between two CUTE results.
  val finalBeatFire = active && io.out.fire && beat === (totalBeats - 1).U
  val tileStart = !active || finalBeatFire
  tileQueue.io.deq.ready := tileStart
  io.out.valid := active
  io.out.bits.data := data.reverse.reduce(Cat(_, _))
  io.out.bits.tileId := tileId
  io.out.bits.microblockId := microblockId
  io.out.bits.sew := 2.U
  io.out.bits.kComplete := kComplete
  io.out.bits.taskLast := taskLast && beat === (totalBeats - 1).U
  io.out.bits.scaleTag := scaleTag
  io.out.bits.biasTag := biasTag
  io.out.bits.row := row
  io.out.bits.col := col
  io.out.bits.tileDim := tileDim.U
  io.out.bits.validLanes := valid.asUInt
  io.out.bits.microblockBeat := microblockBeat
  io.out.bits.microblockBeats := (microDim * beatsPerMicroRow).U

  when(tileQueue.io.deq.fire && tileStart) {
    tile := tileQueue.io.deq.bits.data
    tileId := tileQueue.io.deq.bits.tileId
    kComplete := tileQueue.io.deq.bits.kComplete
    taskLast := tileQueue.io.deq.bits.taskLast
    scaleTag := tileQueue.io.deq.bits.scaleTag
    biasTag := tileQueue.io.deq.bits.biasTag
    beat := 0.U
    active := true.B
  }
  when(io.out.fire && !finalBeatFire) {
    beat := beat + 1.U
  }
  when(io.out.fire && finalBeatFire && !tileQueue.io.deq.fire) {
    active := false.B
  }
}
