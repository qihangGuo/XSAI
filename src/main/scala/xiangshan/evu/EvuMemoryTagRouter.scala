package xiangshan.evu

import chisel3._
import chisel3.util._

/** Matrix/VMU local tags share all HBL2 sources, with ownership kept until Ack. */
class EvuMemoryTagRouter(p: EvuParams) extends Module {
  val io = IO(new Bundle {
    val clients = Vec(2, Flipped(new EvuLineMemoryIO(p)))
    val memory = new EvuLineMemoryIO(p)
    val flush = Input(Bool())
    val adapterBusy = Input(Bool())
    val lookupTag = Input(UInt(p.memoryTagWidth.W))
    val lookupOwner = Output(Bool())
    val busy = Output(Bool())
    val tagStall = Output(Bool())
    val occupancy = Output(UInt(log2Ceil(p.maxMemOutstanding + 1).W))
  })
  val live = RegInit(VecInit.fill(p.maxMemOutstanding)(false.B))
  val owner = Reg(Vec(p.maxMemOutstanding, Bool()))
  val clientTag = Reg(Vec(p.maxMemOutstanding, UInt(p.memoryTagWidth.W)))
  val draining = RegInit(false.B)
  val free = ~live.asUInt
  val tag = PriorityEncoder(free)
  val arbiter = Module(new RRArbiter(new EvuLineMemoryRequest(p), 2))
  for (i <- 0 until 2) { arbiter.io.in(i) <> io.clients(i).req }
  io.memory.req.valid := arbiter.io.out.valid && free.orR && !io.flush && !draining
  io.memory.req.bits := arbiter.io.out.bits
  io.memory.req.bits.tag := tag
  arbiter.io.out.ready := io.memory.req.ready && free.orR && !io.flush && !draining
  when(io.memory.req.fire) {
    live(tag) := true.B
    owner(tag) := arbiter.io.chosen
    clientTag(tag) := arbiter.io.out.bits.tag
  }
  val responseTag = io.memory.resp.bits.tag
  for (i <- 0 until 2) {
    io.clients(i).resp.valid := io.memory.resp.valid && live(responseTag) && owner(responseTag) === i.U &&
      !io.flush && !draining
    io.clients(i).resp.bits := io.memory.resp.bits
    io.clients(i).resp.bits.tag := clientTag(responseTag)
  }
  io.memory.resp.ready := live(responseTag) &&
    (io.flush || draining || Mux(owner(responseTag), io.clients(1).resp.ready, io.clients(0).resp.ready))
  when(io.memory.resp.valid) { assert(live(responseTag), "HBL2 response has no global tag owner") }
  when(io.memory.resp.fire) { live(responseTag) := false.B }
  // The line adapter discards cancelled client responses, but drains A/D beats
  // already accepted by WidthWidget. Keep their source/translation ownership.
  // Saturn retains its local tags on flush, so retire them only after that
  // drain. Its hblDrop bits consume these cancellation Acks without forwarding
  // them into the reset load/store queues. The matrix buffer resets locally.
  when(io.flush) { draining := true.B }
  when(draining && !io.adapterBusy && !io.flush) {
    val cancelledTag = PriorityEncoder(live.asUInt)
    io.clients(1).resp.valid := live.asUInt.orR && owner(cancelledTag)
    io.clients(1).resp.bits := 0.U.asTypeOf(io.clients(1).resp.bits)
    io.clients(1).resp.bits.tag := clientTag(cancelledTag)
    when(live.asUInt.orR && (!owner(cancelledTag) || io.clients(1).resp.ready)) {
      live(cancelledTag) := false.B
    }
    when(!live.asUInt.orR) { draining := false.B }
  }
  io.lookupOwner := owner(io.lookupTag)
  io.busy := live.asUInt.orR || draining
  io.tagStall := arbiter.io.out.valid && !free.orR && !io.flush && !draining
  io.occupancy := PopCount(live)
}
