package xiangshan.evu

import chisel3._
import chisel3.util._

/** Up to 64 logical bytes; the HBL2-facing instance always carries a full aligned line. */
class EvuLineMemoryRequest(p: EvuParams) extends Bundle {
  val jobId = UInt(log2Ceil(p.commandDepth).W)
  val epoch = UInt(2.W)
  val addr = UInt(64.W)
  val data = UInt(512.W)
  val mask = UInt(64.W)
  val bytes = UInt(7.W)
  val write = Bool()
  val tag = UInt(p.memoryTagWidth.W)
}

class EvuLineMemoryResponse(p: EvuParams) extends Bundle {
  val data = UInt(512.W)
  val error = Bool()
  val fault = UInt(4.W)
  val tag = UInt(p.memoryTagWidth.W)
}

class EvuLineMemoryIO(p: EvuParams) extends Bundle {
  val req = Decoupled(new EvuLineMemoryRequest(p))
  val resp = Flipped(Decoupled(new EvuLineMemoryResponse(p)))
}

/**
  * Converts EVU accesses into HBL2's 64-byte Get/PutFullData transactions.
  * Each source remains owned through the final client response. Different
  * sources can run concurrently, except overlapping accesses involving a
  * store: those are serialized for the entire read/merge/write sequence.
  * Partial stores require exclusive software ownership of the destination
  * lines against CPU/CUTE writers; the external read/modify/write is not atomic.
  */
class EvuHbl2LineAdapter(p: EvuParams) extends Module {
  val io = IO(new Bundle {
    val client = Flipped(new EvuLineMemoryIO(p))
    val memory = new EvuLineMemoryIO(p)
    val flush = Input(Bool())
    val busy = Output(Bool())
    val partialWrite = Output(Bool())
  })

  private val n = p.maxMemOutstanding
  val idle :: readReq :: readWait :: writeReq :: writeWait :: respond :: Nil = Enum(6)
  val states = RegInit(VecInit.fill(n)(idle))
  val requests = Reg(Vec(n, new EvuLineMemoryRequest(p)))
  val second = RegInit(VecInit.fill(n)(false.B))
  val crosses = Reg(Vec(n, Bool()))
  val dropped = RegInit(VecInit.fill(n)(false.B))
  val firstData = Reg(Vec(n, UInt(512.W)))
  val putData = Reg(Vec(n, UInt(512.W)))
  val results = Reg(Vec(n, new EvuLineMemoryResponse(p)))

  val shiftedData = Wire(Vec(n, UInt(1024.W)))
  val shiftedMask = Wire(Vec(n, UInt(128.W)))
  val partData = Wire(Vec(n, UInt(512.W)))
  val partMask = Wire(Vec(n, UInt(64.W)))
  for (i <- 0 until n) {
    shiftedData(i) := Cat(0.U(512.W), requests(i).data) << (requests(i).addr(5, 0) << 3)
    shiftedMask(i) := Cat(0.U(64.W), requests(i).mask) << requests(i).addr(5, 0)
    partData(i) := Mux(second(i), shiftedData(i)(1023, 512), shiftedData(i)(511, 0))
    partMask(i) := Mux(second(i), shiftedMask(i)(127, 64), shiftedMask(i)(63, 0))
  }

  val incoming = io.client.req.bits
  val incomingBase = incoming.addr & ~63.U(64.W)
  val incomingCrosses = (incoming.addr(5, 0) +& incoming.bytes) > 64.U
  val conflicts = (0 until n).map { i =>
    val base = requests(i).addr & ~63.U(64.W)
    val overlap = incomingBase === base ||
      (crosses(i) && incomingBase === base + 64.U) ||
      (incomingCrosses && incomingBase + 64.U === base) ||
      (incomingCrosses && crosses(i) && incomingBase + 64.U === base + 64.U)
    states(i) =/= idle && (incoming.write || requests(i).write) && overlap
  }.reduce(_ || _)
  io.client.req.ready := !io.flush && states(incoming.tag) === idle && !conflicts
  when(io.client.req.fire) {
    assert(incoming.bytes > 0.U && incoming.bytes <= 64.U, "Invalid EVU logical memory size")
    requests(incoming.tag) := incoming
    crosses(incoming.tag) := incomingCrosses
    second(incoming.tag) := false.B
    dropped(incoming.tag) := false.B
    results(incoming.tag).data := 0.U
    results(incoming.tag).error := false.B
    results(incoming.tag).fault := EvuFault.None
    results(incoming.tag).tag := incoming.tag
    val mask = (Cat(0.U(64.W), incoming.mask) << incoming.addr(5, 0))(63, 0)
    putData(incoming.tag) := (Cat(0.U(512.W), incoming.data) << (incoming.addr(5, 0) << 3))(511, 0)
    states(incoming.tag) := Mux(incoming.write && !incoming.mask.orR, respond,
      Mux(incoming.write && mask.andR, writeReq, readReq))
  }

  val arbiter = Module(new RRArbiter(new EvuLineMemoryRequest(p), n))
  // Never flush this queue: WidthWidget may already have sent the first A
  // beat while its 64-byte input is stalled. Such requests must drain.
  val outgoing = Module(new Queue(new EvuLineMemoryRequest(p), 1))
  outgoing.io.enq <> arbiter.io.out
  io.memory.req <> outgoing.io.deq
  for (i <- 0 until n) {
    arbiter.io.in(i).valid := !io.flush && (states(i) === readReq || states(i) === writeReq)
    arbiter.io.in(i).bits.addr := (requests(i).addr & ~63.U(64.W)) + Mux(second(i), 64.U, 0.U)
    arbiter.io.in(i).bits.data := putData(i)
    // Bus writes are still PutFullData. Retain the logical byte mask solely
    // for exact difftest attribution after cross-command store combining.
    arbiter.io.in(i).bits.mask := Mux(states(i) === writeReq, partMask(i), Fill(64, 1.U(1.W)))
    arbiter.io.in(i).bits.bytes := 64.U
    arbiter.io.in(i).bits.write := states(i) === writeReq
    arbiter.io.in(i).bits.tag := i.U
    arbiter.io.in(i).bits.jobId := requests(i).jobId
    arbiter.io.in(i).bits.epoch := requests(i).epoch
    when(arbiter.io.in(i).fire) {
      states(i) := Mux(states(i) === readReq, readWait, writeWait)
    }
  }
  io.partialWrite := io.client.req.fire && incoming.write && incoming.mask.orR &&
    (!incoming.mask.andR || incoming.bytes =/= 64.U || incoming.addr(5, 0) =/= 0.U)

  val responses = Module(new RRArbiter(new EvuLineMemoryResponse(p), n))
  io.client.resp <> responses.io.out
  for (i <- 0 until n) {
    responses.io.in(i).valid := states(i) === respond && !io.flush
    responses.io.in(i).bits := results(i)
    when(responses.io.in(i).fire) { states(i) := idle }
  }

  val d = io.memory.resp.bits
  val waiting = states(d.tag) === readWait || states(d.tag) === writeWait
  io.memory.resp.ready := waiting
  when(io.memory.resp.valid) {
    assert(waiting, "EVU received an HBL2 response for a free or non-waiting tag")
  }

  for (i <- 0 until n) {
    def finishPart(): Unit = {
      when(!second(i) && crosses(i)) {
        second(i) := true.B
        putData(i) := shiftedData(i)(1023, 512)
        val mask = shiftedMask(i)(127, 64)
        states(i) := Mux(requests(i).write,
          Mux(!mask.orR, respond, Mux(mask.andR, writeReq, readReq)), readReq)
      }.otherwise { states(i) := respond }
    }
    when(io.memory.resp.fire && d.tag === i.U) {
      when(dropped(i) || io.flush) {
        states(i) := idle
        dropped(i) := false.B
      }.elsewhen(d.error) {
        results(i).error := true.B
        results(i).fault := d.fault
        states(i) := respond
      }.elsewhen(states(i) === readWait && requests(i).write) {
        val mask = FillInterleaved(8, partMask(i))
        putData(i) := (d.data & ~mask) | (partData(i) & mask)
        states(i) := writeReq
      }.otherwise {
        when(states(i) === readWait) {
          firstData(i) := d.data
          val combined = Mux(second(i), Cat(d.data, firstData(i)), Cat(0.U(512.W), d.data))
          results(i).data := (combined >> (requests(i).addr(5, 0) << 3))(511, 0)
        }
        finishPart()
      }
    }
    when(io.flush) {
      when(states(i) === readWait || states(i) === writeWait) {
        when(!(io.memory.resp.fire && d.tag === i.U)) { dropped(i) := true.B }
      }.otherwise {
        states(i) := idle
        dropped(i) := false.B
      }
    }
  }
  io.busy := states.map(_ =/= idle).reduce(_ || _) || outgoing.io.deq.valid
}
