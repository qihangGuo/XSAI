package xiangshan.evu

import chisel3._
import chisel3.util._

class EvuMatrixStoreBeat(p: EvuParams) extends Bundle {
  val addr = UInt(64.W)
  val data = UInt(p.dlenBits.W)
  val mask = UInt(p.dlenBytes.W)
  val last = Bool()
}

/** Address-keyed write combining; acknowledgements, not input-last, release a job. */
class EvuMatrixStoreBuffer(p: EvuParams) extends Module {
  require(p.dlenBytes <= 64, "Matrix result beats must fit in two cache-line fragments")
  private val entries = p.matrixStoreEntries
  private val entryWidth = log2Ceil(entries)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new EvuMatrixStoreBeat(p)))
    val memory = new EvuLineMemoryIO(p)
    val start = Input(Bool())
    val flush = Input(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())
    val fault = Output(Valid(new Bundle {
      val addr = UInt(64.W)
      val cause = UInt(4.W)
    }))
    val merge = Output(Bool())
    val partialWrite = Output(Bool())
  })

  val input = Queue(io.in, p.matrixStoreQueueDepth, flush = Some(io.flush))
  val empty :: collecting :: sealedLine :: waiting :: Nil = Enum(4)
  val states = RegInit(VecInit.fill(entries)(empty))
  val addresses = Reg(Vec(entries, UInt(64.W)))
  val faultAddresses = Reg(Vec(entries, UInt(64.W)))
  val data = Reg(Vec(entries, UInt(512.W)))
  val masks = Reg(Vec(entries, UInt(64.W)))
  val tagLive = RegInit(VecInit.fill(p.maxMemOutstanding)(false.B))
  val tagEntry = Reg(Vec(p.maxMemOutstanding, UInt(entryWidth.W)))
  val highPart = RegInit(false.B)
  val ended = RegInit(false.B)
  val failed = RegInit(false.B)

  // Split unaligned beats before matching. Only byte-enabled lanes participate;
  // an incomplete final line follows the existing HBL2 read/modify/write path.
  val shiftedData = (Cat(0.U(512.W), input.bits.data.pad(512)) << (input.bits.addr(5, 0) << 3))(1023, 0)
  val shiftedMask = (Cat(0.U(64.W), input.bits.mask.pad(64)) << input.bits.addr(5, 0))(127, 0)
  val fragmentData = Mux(highPart, shiftedData(1023, 512), shiftedData(511, 0))
  val fragmentMask = Mux(highPart, shiftedMask(127, 64), shiftedMask(63, 0))
  val fragmentAddr = (input.bits.addr & ~63.U(64.W)) + Mux(highPart, 64.U, 0.U)
  val finalPart = highPart || !shiftedMask(127, 64).orR
  val matches = VecInit((0 until entries).map(i => states(i) =/= empty && addresses(i) === fragmentAddr))
  val free = VecInit(states.map(_ === empty))
  val matchIndex = PriorityEncoder(matches.asUInt)
  val freeIndex = PriorityEncoder(free.asUInt)
  val canMerge = matches.asUInt.orR && states(matchIndex) === collecting
  val fragmentReady = !fragmentMask.orR || failed || canMerge || (!matches.asUInt.orR && free.asUInt.orR)
  val fragmentFire = input.valid && fragmentReady && !io.flush && !ended
  input.ready := fragmentReady && finalPart && !io.flush && !ended
  val index = Mux(matches.asUInt.orR, matchIndex, freeIndex)
  val byteMask = FillInterleaved(8, fragmentMask)
  val mergedMask = Mux(canMerge, masks(index), 0.U) | fragmentMask
  io.merge := fragmentFire && fragmentMask.orR && !failed && canMerge
  when(fragmentFire) {
    highPart := !finalPart
    when(fragmentMask.orR && !failed) {
      addresses(index) := fragmentAddr
      when(!canMerge) { faultAddresses(index) := Mux(highPart, fragmentAddr, input.bits.addr) }
      data(index) := (Mux(canMerge, data(index), 0.U) & ~byteMask) | (fragmentData & byteMask)
      masks(index) := mergedMask
      states(index) := Mux(mergedMask.andR, sealedLine, collecting)
    }
    when(finalPart && input.bits.last) { ended := true.B }
  }

  // Pressure eviction guarantees progress even if the complementary half-line
  // is far away in the CUTE traversal. Never evict the entry being merged.
  val pressure = input.valid && !matches.asUInt.orR && !free.asUInt.orR
  val freeTags = ~tagLive.asUInt
  val tag = PriorityEncoder(freeTags)
  val arbiter = Module(new RRArbiter(new EvuLineMemoryRequest(p), entries))
  for (i <- 0 until entries) {
    val evict = states(i) === collecting && (ended || pressure) &&
      !(input.valid && matches(i) && !ended)
    arbiter.io.in(i).valid := !io.flush && !failed && (states(i) === sealedLine || evict)
    arbiter.io.in(i).bits.addr := addresses(i)
    arbiter.io.in(i).bits.data := data(i)
    arbiter.io.in(i).bits.mask := masks(i)
    arbiter.io.in(i).bits.bytes := 64.U
    arbiter.io.in(i).bits.write := true.B
    arbiter.io.in(i).bits.tag := tag
    when(arbiter.io.in(i).fire) { states(i) := waiting }
  }
  io.memory.req.valid := arbiter.io.out.valid && freeTags.orR
  io.memory.req.bits := arbiter.io.out.bits
  arbiter.io.out.ready := io.memory.req.ready && freeTags.orR
  io.partialWrite := io.memory.req.fire && !io.memory.req.bits.mask.andR
  when(io.memory.req.fire) {
    tagLive(tag) := true.B
    tagEntry(tag) := arbiter.io.chosen
  }

  val responseTag = io.memory.resp.bits.tag
  val responseEntry = tagEntry(responseTag)
  io.memory.resp.ready := tagLive(responseTag) && !io.flush
  io.fault.valid := io.memory.resp.fire && io.memory.resp.bits.error && !failed
  io.fault.bits.addr := faultAddresses(responseEntry)
  io.fault.bits.cause := io.memory.resp.bits.fault
  when(io.memory.resp.valid && !io.flush) {
    assert(tagLive(responseTag), "Matrix write response has no live local tag")
  }
  when(io.memory.resp.fire) {
    tagLive(responseTag) := false.B
    states(responseEntry) := empty
    when(io.memory.resp.bits.error) { failed := true.B }
  }
  when(failed) {
    for (i <- 0 until entries) {
      when(states(i) =/= waiting) { states(i) := empty }
    }
  }
  io.busy := input.valid || states.map(_ =/= empty).reduce(_ || _) || tagLive.asUInt.orR
  io.done := ended && !io.busy && !io.flush
  when(io.start) {
    assert(!io.busy, "A matrix job started before its stores drained")
    ended := false.B
    failed := false.B
  }
  when(io.flush) {
    states.foreach(_ := empty)
    tagLive.foreach(_ := false.B)
    highPart := false.B
    ended := false.B
    failed := false.B
  }
}
