package xiangshan.evu.mem

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tile._
import freechips.rocketchip.util._
import xiangshan.evu.common._

object AgePriorityEncoder
{
  def apply(in: Seq[Bool], head: UInt): UInt = {
    val n = in.size
    val width = log2Ceil(in.size)
    val n_padded = 1 << width
    val temp_vec = (0 until n_padded).map(i => if (i < n) in(i) && i.U >= head else false.B) ++ in
    val idx = PriorityEncoder(temp_vec)
    idx(width-1, 0) //discard msb
  }
}

class LoadOrderBuffer(nEntries: Int, nRobEntries: Int)(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  require(nEntries > 0, "Queue must have non-negative number of entries")
  require(nRobEntries <= nEntries)

  val tagBits = log2Ceil(nEntries)
  val io = IO(new Bundle {
    val reserve = Decoupled(UInt(tagBits.W))
    val entry = Input(new IFQEntry)
    val push = Flipped(Decoupled(new Bundle {
      val data = UInt(mLen.W)
      val tag = UInt(tagBits.W)
    }))

    val replay_liq_id = Output(UInt(log2Ceil(vParams.vliqEntries).W))
    val replay = Decoupled(new MemRequest(mLenB, dmemTagBits))
    val deq = Decoupled(new IFQEntry)
    val deq_data = Output(UInt(mLen.W))
    val busy = Output(Bool())
  })

  val simpleRob = nEntries == nRobEntries

  val valids = RegInit(VecInit.fill(nEntries)(false.B))
  val entries = Reg(Vec(nEntries, new IFQEntry))
  val rob_idxs = Reg(Vec(nEntries, UInt(log2Ceil(nRobEntries).W)))
  val rob = Reg(Vec(nRobEntries, UInt(mLen.W)))
  val rob_valids = RegInit(VecInit.fill(nRobEntries)(false.B))

  val enq_ptr = Counter(nEntries)
  val deq_ptr = Counter(nEntries)
  val maybe_full = RegInit(false.B)
  val rob_full = rob_valids.andR && (!simpleRob).B
  val rob_next = PriorityEncoder(~rob_valids)
  val ptr_match = enq_ptr.value === deq_ptr.value
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full

  io.busy := !empty
  io.reserve.valid := !full
  io.reserve.bits := enq_ptr.value
  when (io.reserve.fire) {
    entries(enq_ptr.value) := io.entry
    valids(enq_ptr.value) := io.entry.masked
    enq_ptr.inc()
  }

  io.deq.valid := !empty && (valids(deq_ptr.value) || (io.push.fire && io.push.bits.tag === deq_ptr.value))
  io.deq.bits := entries(deq_ptr.value)
  val rob_deq_idx = if (simpleRob) deq_ptr.value else rob_idxs(deq_ptr.value)
  io.deq_data := Mux(valids(deq_ptr.value), rob(rob_deq_idx), io.push.bits.data)

  val push_bypass = deq_ptr.value === io.push.bits.tag && io.deq.ready
  io.push.ready := !rob_full || push_bypass

  val rob_push_idx = if (simpleRob) io.push.bits.tag else rob_next
  when (io.push.fire && !push_bypass) {
    assert(!rob_full)
    valids(io.push.bits.tag) := true.B
    rob_idxs(io.push.bits.tag) := rob_next
    rob_valids(rob_push_idx) := true.B
    rob(rob_push_idx) := io.push.bits.data
  }

  when (io.deq.fire) {
    deq_ptr.inc()
    valids(deq_ptr.value) := false.B
    when (valids(deq_ptr.value) && !entries(deq_ptr.value).masked) {
      rob_valids(rob_deq_idx) := false.B
    }
  }

  // Responses are backpressured instead of dropped and replayed. Keep this
  // legacy port tied off so the request-side structure remains unchanged.
  io.replay_liq_id := 0.U
  io.replay.valid := false.B
  io.replay.bits := DontCare

  when (io.reserve.fire =/= io.deq.fire) {
    maybe_full := io.reserve.fire
  }
}
