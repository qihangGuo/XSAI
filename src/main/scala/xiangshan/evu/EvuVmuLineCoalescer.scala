package xiangshan.evu

import chisel3._
import chisel3.util._

/** Loads reuse only within a job. Disjoint stores may combine across jobs;
  * every logical owner is acknowledged only after the physical bus response. */
class EvuVmuLineCoalescer(p: EvuParams) extends Module {
  private val n = p.maxMemOutstanding
  val io = IO(new Bundle {
    val client = Flipped(new EvuLineMemoryIO(p))
    val memory = new EvuLineMemoryIO(p)
    val storePending = Input(Bool())
    val flush = Input(Bool())
    val drain = Input(Bool())
    val busy = Output(Bool())
    val loadMerge = Output(Bool())
    val storeMerge = Output(Bool())
    val bufferFull = Output(Bool())
    val ackWait = Output(Bool())
  })

  if (!p.vmuLineCoalescing) {
    io.memory <> io.client
    io.busy := false.B
    io.loadMerge := false.B
    io.storeMerge := false.B
    io.bufferFull := false.B
    io.ackWait := false.B
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
    val draining = RegInit(false.B)
    val incoming = io.client.req.bits
    val base = incoming.addr & ~63.U(64.W)
    val fits = (incoming.addr(5, 0) +& incoming.bytes) <= 64.U
    val shiftedMask = (incoming.mask << incoming.addr(5, 0))(63, 0)
    val shiftedData = (incoming.data << (incoming.addr(5, 0) << 3))(511, 0)
    val sameJob = VecInit((0 until n).map(i => requests(i).jobId === incoming.jobId &&
      requests(i).epoch === incoming.epoch))
    val sameLine = VecInit((0 until n).map(i => states(i) =/= empty &&
      !passthrough(i) && fits && requests(i).addr === base))
    val mergeable = VecInit((0 until n).map(i => sameLine(i) &&
      requests(i).write === incoming.write && Mux(incoming.write,
        states(i) === collecting && requests(i).epoch === incoming.epoch &&
          !(requests(i).mask & shiftedMask).orR,
        sameJob(i))))
    val free = VecInit((0 until n).map(i => states(i) === empty ||
      (states(i) === responded && owners(i) === 0.U)))
    val merging = mergeable.asUInt.orR
    val index = Mux(merging, PriorityEncoder(mergeable.asUInt), PriorityEncoder(free.asUInt))
    // Include both lines for generic crossing requests. Production MLEN beats
    // are aligned, but a passthrough RMW must not race an adjacent line owner.
    val incomingLastLine = (incoming.addr +& (incoming.bytes - 1.U)) >> 6
    val overlaps = VecInit((0 until n).map { i =>
      val lastLine = (requests(i).addr +& (requests(i).bytes - 1.U)) >> 6
      states(i) =/= empty && (requests(i).addr >> 6) <= incomingLastLine &&
        (incoming.addr >> 6) <= lastLine
    })
    // A store already sent to memory must finish before a later overlapping access.
    val locked = (0 until n).map(i => overlaps(i) && !mergeable(i) &&
      (incoming.write || requests(i).write) && owners(i).orR).reduce(_ || _)
    io.client.req.ready := !io.flush && !draining &&
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
      // Only collect while an actual store can advance through the VMU.
      // A configured command window alone does not provide a merge candidate.
      // Timeout still bounds waiting on a candidate whose data is delayed.
      val seal = !requests(i).write || passthrough(i) || requests(i).mask.andR ||
        !io.storePending || (io.client.req.valid && !mergeable(i)) ||
        io.drain || pressure || (io.client.req.valid && locked) || age(i).andR
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
      when(states(i) === responded && nextOwners === 0.U) {
        states(i) := empty
      }
    }
    io.memory.req <> arbiter.io.out

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

    when(draining && !states.map(_ =/= empty).reduce(_ || _) && !io.flush) { draining := false.B }
    when(io.flush) {
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
    io.bufferFull := io.client.req.valid && !merging && !free.asUInt.orR
    io.ackWait := states.map(_ === waiting).reduce(_ || _)
  }
}
