package xiangshan.evu

import chisel3._
import chisel3.util._

/** Command-local line reuse; every logical owner is acknowledged after the bus response. */
class EvuVmuLineCoalescer(p: EvuParams) extends Module {
  private val n = p.maxMemOutstanding
  val io = IO(new Bundle {
    val client = Flipped(new EvuLineMemoryIO(p))
    val memory = new EvuLineMemoryIO(p)
    val start = Input(Valid(UInt(log2Ceil(p.commandDepth).W)))
    val end = Input(Bool())
    val flush = Input(Bool())
    val busy = Output(Bool())
    val loadMerge = Output(Bool())
    val storeMerge = Output(Bool())
  })

  if (!p.vmuLineCoalescing) {
    io.memory <> io.client
    io.busy := false.B
    io.loadMerge := false.B
    io.storeMerge := false.B
  } else {
    val empty :: collecting :: waiting :: responded :: Nil = Enum(4)
    val states = RegInit(VecInit.fill(n)(empty))
    val requests = Reg(Vec(n, new EvuLineMemoryRequest(p)))
    val owners = RegInit(VecInit.fill(n)(0.U(n.W)))
    val offsets = Reg(Vec(n, UInt(6.W)))
    val faults = Reg(Vec(n, new EvuLineMemoryResponse(p)))
    val passthrough = Reg(Vec(n, Bool()))
    val age = RegInit(VecInit.fill(n)(0.U(5.W)))
    val liveTags = RegInit(0.U(n.W))
    val ended = RegInit(true.B)
    val draining = RegInit(false.B)
    val commandId = Reg(UInt(log2Ceil(p.commandDepth).W))
    val jobs = Reg(Vec(n, UInt(log2Ceil(p.commandDepth).W)))
    val incoming = io.client.req.bits
    val base = incoming.addr & ~63.U(64.W)
    val fits = (incoming.addr(5, 0) +& incoming.bytes) <= 64.U
    val sameLine = VecInit((0 until n).map(i => states(i) =/= empty &&
      !passthrough(i) && fits && requests(i).addr === base && jobs(i) === commandId))
    val mergeable = VecInit((0 until n).map(i => sameLine(i) &&
      requests(i).write === incoming.write && (!incoming.write || states(i) === collecting)))
    val free = VecInit((0 until n).map(i => states(i) === empty ||
      (states(i) === responded && owners(i) === 0.U)))
    val merging = mergeable.asUInt.orR
    val index = Mux(merging, PriorityEncoder(mergeable.asUInt), PriorityEncoder(free.asUInt))
    // A store already sent to memory must finish before a later same-line store.
    val locked = sameLine.asUInt.orR && !merging
    io.client.req.ready := !io.flush && !draining && !ended &&
      !liveTags(incoming.tag) && !locked && (merging || free.asUInt.orR)
    val accept = io.client.req.fire
    val acceptedTag = Mux(accept, UIntToOH(incoming.tag, n), 0.U(n.W))

    val responseTags = (0 until n).map(i => Mux(states(i) === responded, owners(i), 0.U(n.W))).reduce(_ | _)
    val responseTag = PriorityEncoder(responseTags)
    val responseEntry = PriorityEncoder(VecInit((0 until n).map(i =>
      states(i) === responded && owners(i)(responseTag))).asUInt)
    io.client.resp.valid := responseTags.orR && !io.flush
    io.client.resp.bits := faults(responseEntry)
    io.client.resp.bits.tag := responseTag
    io.client.resp.bits.data := faults(responseEntry).data >> (offsets(responseTag) << 3)
    val returnedTag = Mux(io.client.resp.fire, UIntToOH(responseTag, n), 0.U(n.W))
    liveTags := (liveTags | acceptedTag) & ~returnedTag

    val pressure = io.client.req.valid && (!free.asUInt.orR || liveTags.andR)
    val arbiter = Module(new RRArbiter(new EvuLineMemoryRequest(p), n))
    for (i <- 0 until n) {
      val completingLine = accept && index === i.U
      // Timeout also guarantees progress if a VMU cannot produce the next beat
      // until an earlier Ack frees its own bookkeeping entries.
      val seal = !requests(i).write || passthrough(i) || requests(i).mask.andR ||
        ended || pressure || age(i).andR
      arbiter.io.in(i).valid := states(i) === collecting && seal &&
        !(completingLine && incoming.write) && !io.flush && !draining
      arbiter.io.in(i).bits := requests(i)
      arbiter.io.in(i).bits.tag := i.U
      when(arbiter.io.in(i).fire) { states(i) := waiting }
      when(states(i) === collecting && !age(i).andR) { age(i) := age(i) + 1.U }

      val additions = Mux(accept && index === i.U, acceptedTag, 0.U(n.W))
      val removals = Mux(io.client.resp.fire && responseEntry === i.U, returnedTag, 0.U(n.W))
      val nextOwners = (owners(i) | additions) & ~removals
      owners(i) := nextOwners
      when(states(i) === responded && nextOwners === 0.U && (ended || requests(i).write || draining)) {
        states(i) := empty
      }
    }
    io.memory.req <> arbiter.io.out

    val shiftedMask = (incoming.mask << incoming.addr(5, 0))(63, 0)
    val shiftedData = (incoming.data << (incoming.addr(5, 0) << 3))(511, 0)
    when(accept) {
      assert(incoming.bytes > 0.U && incoming.bytes <= 64.U)
      offsets(incoming.tag) := Mux(fits, incoming.addr(5, 0), 0.U)
      age(index) := 0.U
      when(!merging) {
        requests(index) := incoming
        requests(index).addr := Mux(fits, base, incoming.addr)
        requests(index).bytes := Mux(fits, 64.U, incoming.bytes)
        requests(index).data := Mux(fits, shiftedData, incoming.data)
        requests(index).mask := Mux(fits, shiftedMask, incoming.mask)
        passthrough(index) := !fits
        jobs(index) := commandId
        states(index) := collecting
      }.elsewhen(incoming.write) {
        val mask = FillInterleaved(8, shiftedMask)
        requests(index).data := (requests(index).data & ~mask) | (shiftedData & mask)
        requests(index).mask := requests(index).mask | shiftedMask
      }
    }
    val busTag = io.memory.resp.bits.tag
    io.memory.resp.ready := states(busTag) === waiting
    when(io.memory.resp.valid) { assert(states(busTag) === waiting, "VMU line response has no owner") }
    when(io.memory.resp.fire) {
      faults(busTag) := io.memory.resp.bits
      when(draining || io.flush) {
        faults(busTag).error := false.B
        faults(busTag).fault := EvuFault.None
      }
      states(busTag) := responded
    }

    when(io.start.valid) {
      assert(!io.busy, "VMU command changed before all line owners drained")
      ended := false.B
      commandId := io.start.bits
    }
    when(io.end) { ended := true.B }
    when(draining && !states.map(_ =/= empty).reduce(_ || _) && !io.flush) { draining := false.B }
    when(io.flush) {
      ended := true.B
      draining := true.B
      for (i <- 0 until n) {
        when(states(i) === collecting) {
          states(i) := responded
          faults(i) := 0.U.asTypeOf(faults(i))
        }
        when(states(i) === responded && owners(i) === 0.U) { states(i) := empty }
      }
    }
    io.busy := liveTags.orR || states.map(_ =/= empty).reduce(_ || _) || draining
    io.loadMerge := accept && merging && !incoming.write
    io.storeMerge := accept && merging && incoming.write
  }
}
