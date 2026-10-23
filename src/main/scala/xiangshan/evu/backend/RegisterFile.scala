package xiangshan.evu.backend

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tile.{CoreBundle, CoreModule}
import freechips.rocketchip.util._
import xiangshan.evu.common._
import utility.sram.SRAMTemplate

// A request accepted in cycle N returns in cycle N+1, without response backpressure.
class SramReadIO(implicit p: Parameters) extends CoreBundle()(p) with HasVectorParams {
  val req = Decoupled(new VectorReadReq)
  val resp = Flipped(Valid(UInt(dLen.W)))
}

// Legacy clients consume data on req.fire; acknowledge only the matching response.
class RegisterReadCollector(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val client = Flipped(new VectorReadIO)
    val memory = new SramReadIO
  })

  val pending = RegInit(false.B)
  val requestedEg = Reg(UInt(log2Ceil(egsTotal).W))
  io.memory.req.valid := io.client.req.valid && !pending && !reset.asBool
  io.memory.req.bits := io.client.req.bits
  io.client.req.ready := pending && io.memory.resp.valid &&
    io.client.req.valid && io.client.req.bits.eg === requestedEg && !reset.asBool
  io.client.resp := io.memory.resp.bits

  when (io.memory.req.fire) {
    pending := true.B
    requestedEg := io.memory.req.bits.eg
  } .elsewhen (io.memory.resp.valid) {
    // A withdrawn or changed request discards its old response before retrying.
    pending := false.B
  }
}

class OldestRRArbiter(val n: Int)(implicit p: Parameters) extends Module {
  val io = IO(new ArbiterIO(new VectorReadReq, n))

  require(n > 0)
  val arb = Module(new RRArbiter(new VectorReadReq, n))
  for (i <- 0 until n) {
    arb.io.in(i).valid := io.in(i).valid
    arb.io.in(i).bits := io.in(i).bits
  }

  val oldest = VecInit(io.in.map(i => i.valid && i.bits.oldest))
  val oldestWinner = PriorityEncoderOH(oldest)
  val useOldest = oldest.asUInt.orR

  io.out.valid := Mux(useOldest, true.B, arb.io.out.valid)
  io.out.bits := Mux(useOldest,
    Mux1H(oldestWinner, io.in.map(_.bits)),
    arb.io.out.bits)
  io.chosen := Mux(useOldest,
    PriorityEncoder(oldestWinner),
    arb.io.chosen)
  arb.io.out.ready := io.out.ready && !useOldest

  for (i <- 0 until n) {
    io.in(i).ready := Mux(useOldest,
      oldestWinner(i) && io.out.ready,
      arb.io.in(i).ready)
  }
}

class RegisterReadXbar(n: Int, banks: Int)(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val in = Vec(n, Flipped(new SramReadIO))
    val out = Vec(banks, new SramReadIO)
  })

  val arbs = Seq.fill(banks) { Module(new OldestRRArbiter(n)) }
  for (i <- 0 until banks) {
    io.out(i).req <> arbs(i).io.out
  }

  val bankOffset = log2Ceil(banks)

  for (i <- 0 until n) {
    val bank_sel = if (bankOffset == 0) true.B else UIntToOH(io.in(i).req.bits.eg(bankOffset-1,0))
    for (j <- 0 until banks) {
      arbs(j).io.in(i).valid := io.in(i).req.valid && bank_sel(j)
      arbs(j).io.in(i).bits.eg := io.in(i).req.bits.eg >> bankOffset
      arbs(j).io.in(i).bits.oldest := io.in(i).req.bits.oldest
    }
    io.in(i).req.ready := Mux1H(bank_sel, arbs.map(_.io.in(i).ready))
    val responseBank = RegEnable(bank_sel, io.in(i).req.fire)
    val responsePending = RegNext(io.in(i).req.fire, false.B)
    io.in(i).resp.valid := responsePending && Mux1H(responseBank, io.out.map(_.resp.valid))
    io.in(i).resp.bits := Mux1H(responseBank, io.out.map(_.resp.bits))
  }
}

// Logical requests address one dLen-bit element group.
class RegisterFileIO(
    reads: Seq[Int],
    maskReads: Seq[Int],
    pipeWrites: Int,
    llWrites: Int,
    maxDepth: Int
)(implicit p: Parameters) extends CoreBundle()(p) with HasVectorParams {
  val read = MixedVec(reads.map(rc => Vec(rc, Flipped(new VectorReadIO))))
  val mask_read = MixedVec(maskReads.map(rc => Vec(rc, Flipped(new VectorReadIO))))
  val pipe_write_reqs = Vec(pipeWrites, Flipped(new VectorPipeWriteReqIO(maxDepth)))

  val pipe_writes = Vec(pipeWrites, Input(Valid(new VectorWrite(dLen))))
  val ll_writes = Vec(llWrites, Flipped(Decoupled(new VectorWrite(dLen))))
  val debug = Option.when(vParams.debugVrf)(Output(Vec(egsTotal, UInt(dLen.W))))
}

class RegisterFile(reads: Seq[Int], maskReads: Seq[Int], pipeWrites: Int, llWrites: Int, maxDepth: Int,
                   sramSuffix: String = "evu_vrf")(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {

  val io = IO(new RegisterFileIO(reads, maskReads, pipeWrites, llWrites, maxDepth))
  val vrf = Module(new AraRegisterFile(reads, maskReads, pipeWrites, llWrites, maxDepth, sramSuffix))
  io.pipe_write_reqs <> vrf.io.pipe_write_reqs
  io.pipe_writes <> vrf.io.pipe_writes
  io.ll_writes <> vrf.io.ll_writes
  io.debug.foreach(_ := vrf.io.debug.get)

  private def collect(client: VectorReadIO, memory: SramReadIO): Unit = {
    val collector = Module(new RegisterReadCollector)
    collector.io.client <> client
    collector.io.memory <> memory
  }
  for ((clients, ports) <- io.read.zip(vrf.io.read); (client, port) <- clients.zip(ports)) {
    collect(client, port)
  }
  for ((clients, ports) <- io.mask_read.zip(vrf.io.mask_read); (client, port) <- clients.zip(ports)) {
    collect(client, port)
  }
}

class SramRegisterFileIO(
    reads: Seq[Int],
    maskReads: Seq[Int],
    pipeWrites: Int,
    llWrites: Int,
    maxDepth: Int
)(implicit p: Parameters) extends CoreBundle()(p) with HasVectorParams {
  val read = MixedVec(reads.map(rc => Vec(rc, Flipped(new SramReadIO))))
  val mask_read = MixedVec(maskReads.map(rc => Vec(rc, Flipped(new SramReadIO))))
  val pipe_write_reqs = Vec(pipeWrites, Flipped(new VectorPipeWriteReqIO(maxDepth)))
  val pipe_writes = Vec(pipeWrites, Input(Valid(new VectorWrite(dLen))))
  val ll_writes = Vec(llWrites, Flipped(Decoupled(new VectorWrite(dLen))))
  val debug = Option.when(vParams.debugVrf)(Output(Vec(egsTotal, UInt(dLen.W))))
}

// Each element group spans 64-bit lanes with eight banks per lane.
class AraRegisterFile(
    reads: Seq[Int],
    maskReads: Seq[Int],
    pipeWrites: Int,
    llWrites: Int,
    maxDepth: Int,
    sramSuffix: String = "evu_vrf"
)(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {

  private val sliceBits = 64
  private val sliceBytes = sliceBits / 8
  private val laneCount = dLen / sliceBits
  private val banksPerLane = 8
  private val bankBits = log2Ceil(banksPerLane)
  private val rowsPerBank = egsTotal / banksPerLane
  private val rowBits = log2Ceil(rowsPerBank max 2)

  require(dLen % sliceBits == 0, "Ara-style EVU VRF requires dLen to be a multiple of 64")
  require(egsTotal % banksPerLane == 0, "EVU element groups must divide evenly across eight banks")
  require(vParams.vrfBanking == banksPerLane,
    "Ara-style EVU VRF requires the logical reservation width to match its eight physical banks")

  val io = IO(new SramRegisterFileIO(reads, maskReads, pipeWrites, llWrites, maxDepth))

  private val storage = Seq.tabulate(laneCount, banksPerLane) { (lane, bank) =>
    Module(new SRAMTemplate(UInt(8.W), rowsPerBank, way = sliceBytes,
      singlePort = true, suffix = Some(sramSuffix))).suggestName(s"storage_${lane}_${bank}")
  }

  // Debug snapshots use a shadow copy to avoid adding memory read ports.
  private val debugShadow = Option.when(vParams.debugVrf) {
    RegInit(VecInit.fill(egsTotal)(0.U(dLen.W)))
  }

  private def bankOf(eg: UInt): UInt = eg(bankBits - 1, 0)
  private def rowOf(eg: UInt): UInt = (eg >> bankBits)(rowBits - 1, 0)

  private val readXbars = reads.map { rc => Module(new RegisterReadXbar(rc, banksPerLane)) }
  private val maskXbars = maskReads.map { rc => Module(new RegisterReadXbar(rc, banksPerLane)) }

  reads.zipWithIndex.foreach { case (_, i) =>
    readXbars(i).io.in <> io.read(i)
  }
  maskReads.zipWithIndex.foreach { case (_, i) =>
    maskXbars(i).io.in <> io.mask_read(i)
  }

  // Mask and data reads share the same physical banks.
  private val allXbars = readXbars.map(_.io.out) ++ maskXbars.map(_.io.out)
  private val requesterCount = allXbars.size
  require(requesterCount > 0, "Ara-style EVU VRF requires at least one read requester")

  // Scheduled pipe writes have no ready; they must take priority.
  private val pipeMatches = Seq.tabulate(banksPerLane) { bank =>
    io.pipe_writes.map(w => w.valid && bankOf(w.bits.eg) === bank.U)
  }
  private val pipeValid = pipeMatches.map(_.orR)
  private val pipeData = pipeMatches.map(matches => Mux1H(matches, io.pipe_writes.map(_.bits.data)))
  private val pipeMask = pipeMatches.map(matches => Mux1H(matches, io.pipe_writes.map(_.bits.mask)))
  private val pipeEg = pipeMatches.map(matches => Mux1H(matches, io.pipe_writes.map(_.bits.eg)))

  val llArbiters = Seq.tabulate(banksPerLane) { _ =>
    Module(new Arbiter(new VectorWrite(dLen), llWrites))
  }
  // Stage LL writes to break the load-mask-read/write-ready combinational loop.
  private val llPending = Reg(Vec(banksPerLane, new VectorWrite(dLen)))
  private val llPendingValid = RegInit(VecInit.fill(banksPerLane)(false.B))
  private val llReadyByBank = Seq.tabulate(banksPerLane) { _ =>
    Wire(Vec(llWrites, Bool()))
  }
  for (bank <- 0 until banksPerLane) {
    for (j <- 0 until llWrites) {
      llArbiters(bank).io.in(j).valid := io.ll_writes(j).valid &&
        bankOf(io.ll_writes(j).bits.eg) === bank.U && !pipeValid(bank)
      llArbiters(bank).io.in(j).bits := io.ll_writes(j).bits
      llReadyByBank(bank)(j) := llArbiters(bank).io.in(j).ready &&
        !pipeValid(bank) && bankOf(io.ll_writes(j).bits.eg) === bank.U
    }
    llArbiters(bank).io.out.ready := !llPendingValid(bank) || !pipeValid(bank)
    when (llArbiters(bank).io.out.fire) {
      llPending(bank) := llArbiters(bank).io.out.bits
      llPendingValid(bank) := true.B
    } .elsewhen (llPendingValid(bank) && !pipeValid(bank)) {
      llPendingValid(bank) := false.B
    }
  }
  for (j <- 0 until llWrites) {
    io.ll_writes(j).ready := VecInit(llReadyByBank.map(_(j))).asUInt.orR
  }

  // Reserve each physical bank's future write cycles before issuing pipe operations.
  val reqStalls = WireInit(VecInit.fill(pipeWrites)(false.B))
  for (i <- 0 until pipeWrites) {
    io.pipe_write_reqs(i).available := !reqStalls(i)
  }
  for (bank <- 0 until banksPerLane) {
    val scheduler = Module(new PipeScheduler(pipeWrites * 2, maxDepth))
    for (j <- 0 until pipeWrites) {
      val req = io.pipe_write_reqs(j)
      scheduler.io.reqs(j).request := req.request && req.bank_sel(bank) && req.oldest
      scheduler.io.reqs(j).depth := req.pipe_depth
      scheduler.io.reqs(j).fire := req.fire
      when (scheduler.io.reqs(j).request && !scheduler.io.reqs(j).available) {
        reqStalls(j) := true.B
      }

      scheduler.io.reqs(j + pipeWrites).request := req.request && req.bank_sel(bank) && !req.oldest
      scheduler.io.reqs(j + pipeWrites).depth := req.pipe_depth
      scheduler.io.reqs(j + pipeWrites).fire := req.fire
      when (scheduler.io.reqs(j + pipeWrites).request && !scheduler.io.reqs(j + pipeWrites).available) {
        reqStalls(j) := true.B
      }
    }
  }

  // One read grant accesses all 64-bit lanes in parallel.
  for (bank <- 0 until banksPerLane) {
    val readArb = Module(new OldestRRArbiter(requesterCount))
    val readReqs = allXbars.map(_(bank).req)

    // Coalesce equal rows; prefer an oldest requester, then the lowest index.
    for (requester <- 0 until requesterCount) {
      val req = readReqs(requester)
      val sameEarlier = (0 until requester).map { other =>
        readReqs(other).valid && req.valid &&
          readReqs(other).bits.eg === req.bits.eg
      }.foldLeft(false.B)(_ || _)
      val sameOldestEarlier = (0 until requester).map { other =>
        readReqs(other).valid && req.valid && req.bits.oldest &&
          readReqs(other).bits.oldest &&
          readReqs(other).bits.eg === req.bits.eg
      }.foldLeft(false.B)(_ || _)
      val anyOldestInGroup = (0 until requesterCount).map { other =>
        readReqs(other).valid && req.valid && readReqs(other).bits.oldest &&
          readReqs(other).bits.eg === req.bits.eg
      }.foldLeft(false.B)(_ || _)
      val leader = req.valid && Mux(req.bits.oldest,
        !sameOldestEarlier,
        !anyOldestInGroup && !sameEarlier)
      readArb.io.in(requester).valid := leader
      readArb.io.in(requester).bits := req.bits

      req.ready := readArb.io.out.fire && readArb.io.out.bits.eg === req.bits.eg
    }

    val hasPipeWrite = pipeValid(bank)
    val hasLlWrite = llPendingValid(bank) && !hasPipeWrite
    val hasWrite = hasPipeWrite || hasLlWrite
    val writeEg = Mux(hasPipeWrite, pipeEg(bank), llPending(bank).eg)
    // A bank accepts a read or a write per cycle; writes block all reads.
    readArb.io.out.ready := !hasWrite && !reset.asBool

    val writeBits = Wire(new VectorWrite(dLen))
    writeBits.eg := writeEg
    writeBits.data := Mux(hasPipeWrite, pipeData(bank), llPending(bank).data)
    writeBits.mask := Mux(hasPipeWrite, pipeMask(bank), llPending(bank).mask)

    val readData = Cat(storage.map(_(bank).io.r.resp.data.asUInt).reverse)
    for (requester <- 0 until requesterCount) {
      val response = allXbars(requester)(bank).resp
      response.valid := RegNext(readReqs(requester).fire, false.B)
      response.bits := readData
    }

    val writeValid = hasWrite && !reset.asBool
    // Saturn retains a bit-mask interface; the exposed EVU subset writes whole bytes.
    val byteMask = VecInit(writeBits.mask.asBools.grouped(8).map(_.reduce(_ && _)).toSeq).asUInt
    when (writeValid) {
      assert(writeBits.mask === FillInterleaved(8, byteMask),
        "EVU VRF requires whole-byte write masks; partial-bit writes need an upstream merge")
    }

    when (hasPipeWrite) {
      assert(PopCount(pipeMatches(bank)) === 1.U,
        "multiple EVU pipe writes reached one Ara VRF bank")
    }
    for (lane <- 0 until laneCount) {
      val lo = lane * sliceBits
      val sram = storage(lane)(bank)
      sram.io.r(readArb.io.out.fire, readArb.io.out.bits.eg)
      val laneData = writeBits.data(lo + sliceBits - 1, lo).asTypeOf(Vec(sliceBytes, UInt(8.W)))
      sram.io.w(writeValid, laneData, rowOf(writeBits.eg),
        byteMask((lane + 1) * sliceBytes - 1, lane * sliceBytes))
      assert(!sram.io.r.req.valid || sram.io.r.req.ready)
      assert(!sram.io.w.req.valid || sram.io.w.req.ready)
    }

    debugShadow.foreach { shadow =>
      when (writeValid) {
        val old = shadow(writeBits.eg)
        val mask = FillInterleaved(8, byteMask)
        shadow(writeBits.eg) := (writeBits.data & mask) | (old & ~mask)
      }
    }
  }

  io.debug.foreach { snapshot =>
    snapshot.zip(debugShadow.get).foreach { case (out, value) => out := value }
  }
}
