package xiangshan.evu

import chisel3._
import chisel3.util._

/** VMU local tags own HBL2 sources until Ack or completed flush drain. */
class EvuMemoryTagRouter(p: EvuParams) extends Module {
  val io = IO(new Bundle {
    val client = Flipped(new EvuLineMemoryIO(p))
    val memory = new EvuLineMemoryIO(p)
    val flush = Input(Bool())
    val adapterBusy = Input(Bool())
    val busy = Output(Bool())
    val tagStall = Output(Bool())
    val occupancy = Output(UInt(log2Ceil(p.maxMemOutstanding + 1).W))
  })
  val live = RegInit(VecInit.fill(p.maxMemOutstanding)(false.B))
  val clientTag = Reg(Vec(p.maxMemOutstanding, UInt(p.memoryTagWidth.W)))
  val draining = RegInit(false.B)
  val free = ~live.asUInt
  val tag = PriorityEncoder(free)
  io.memory.req.valid := io.client.req.valid && free.orR && !io.flush && !draining
  io.memory.req.bits := io.client.req.bits
  io.memory.req.bits.tag := tag
  io.client.req.ready := io.memory.req.ready && free.orR && !io.flush && !draining
  when(io.memory.req.fire) {
    live(tag) := true.B
    clientTag(tag) := io.client.req.bits.tag
  }
  val responseTag = io.memory.resp.bits.tag
  io.client.resp.valid := io.memory.resp.valid && live(responseTag) && !io.flush && !draining
  io.client.resp.bits := io.memory.resp.bits
  io.client.resp.bits.tag := clientTag(responseTag)
  io.memory.resp.ready := live(responseTag) &&
    (io.flush || draining || io.client.resp.ready)
  when(io.memory.resp.valid) { assert(live(responseTag), "HBL2 response has no global tag owner") }
  when(io.memory.resp.fire) { live(responseTag) := false.B }
  // The line adapter discards cancelled client responses, but drains A/D beats
  // already accepted by WidthWidget. Keep their source/translation ownership.
  // Saturn retains its local tags on flush, so retire them only after that
  // drain. Its hblDrop bits consume these cancellation Acks without forwarding
  // them into the reset load/store queues.
  when(io.flush) { draining := true.B }
  when(draining && !io.adapterBusy && !io.flush) {
    val cancelledTag = PriorityEncoder(live.asUInt)
    io.client.resp.valid := live.asUInt.orR
    io.client.resp.bits := 0.U.asTypeOf(io.client.resp.bits)
    io.client.resp.bits.tag := clientTag(cancelledTag)
    when(io.client.resp.fire) {
      live(cancelledTag) := false.B
    }
    when(!live.asUInt.orR) { draining := false.B }
  }
  io.busy := live.asUInt.orR || draining
  io.tagStall := io.client.req.valid && !free.orR && !io.flush && !draining
  io.occupancy := PopCount(live)
}
