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
  val speculative = Output(Bool())
  val resp = Flipped(Valid(UInt(dLen.W)))
}

// Legacy clients consume data on req.fire; acknowledge only the matching response.
class RegisterReadCollector(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val client = Flipped(new VectorReadIO)
    val memory = new SramReadIO
    val prefetch = Input(Valid(new VectorReadReq))
    val prefetched = Output(Bool())
    val grantWait = Output(Bool())
    val responseWait = Output(Bool())
    val prefetchUsed = Output(Bool())
    val prefetchDiscarded = Output(Bool())
    val demandPrefetchWait = Output(Bool())
  })

  val pending = RegInit(false.B)
  val requestedEg = Reg(UInt(log2Ceil(egsTotal).W))
  val speculative = RegInit(false.B)
  val buffered = RegInit(false.B)
  val data = Reg(UInt(dLen.W))
  val matches = io.client.req.bits.eg === requestedEg
  val available = pending && (buffered || io.memory.resp.valid)
  io.client.req.ready := available && matches && io.client.req.valid && !reset.asBool
  io.client.resp := Mux(buffered, data, io.memory.resp.bits)
  val discard = available && !io.client.req.fire &&
    ((!speculative && !io.client.req.valid) || (io.client.req.valid && !matches))
  val canIssue = !pending || io.client.req.fire || discard
  val demand = io.client.req.valid && !io.client.req.fire
  val usePrefetch = io.prefetch.valid && !demand
  io.memory.req.valid := canIssue &&
    (usePrefetch || demand) && !reset.asBool
  io.memory.req.bits := Mux(usePrefetch, io.prefetch.bits, io.client.req.bits)
  io.memory.speculative := usePrefetch
  io.prefetched := io.memory.req.fire && usePrefetch
  io.prefetchUsed := io.client.req.fire && speculative
  io.prefetchDiscarded := discard && speculative
  io.demandPrefetchWait := io.client.req.valid && pending && speculative && !matches && !available
  val requestingClientEg = io.memory.req.bits.eg === io.client.req.bits.eg
  io.grantWait := io.client.req.valid && io.memory.req.valid && requestingClientEg && !io.memory.req.ready
  io.responseWait := io.client.req.valid && !io.client.req.ready &&
    ((io.memory.req.fire && requestingClientEg) ||
      (matches && pending && !buffered && !io.memory.resp.valid))

  when (io.memory.resp.valid && pending && !buffered) {
    data := io.memory.resp.bits
    buffered := speculative && !discard && !io.client.req.fire
  }
  when (io.client.req.fire || discard) {
    pending := false.B
    buffered := false.B
  }
  when (io.memory.req.fire) {
    pending := true.B
    requestedEg := io.memory.req.bits.eg
    speculative := usePrefetch
    buffered := false.B
  }
}

class VrfReadArbiterIO(n: Int)(implicit p: Parameters) extends ArbiterIO(new VectorReadReq, n) {
  val speculative = Input(Vec(n, Bool()))
}

class OldestRRArbiter(val n: Int)(implicit p: Parameters) extends Module {
  val io = IO(new VrfReadArbiterIO(n))

  require(n > 0)
  val arb = Module(new RRArbiter(new VectorReadReq, n))
  val hasDemand = io.in.zip(io.speculative).map { case (in, spec) => in.valid && !spec }.reduce(_ || _)
  val eligible = io.speculative.map(spec => !hasDemand || !spec)
  for (i <- 0 until n) {
    arb.io.in(i).valid := io.in(i).valid && eligible(i)
    arb.io.in(i).bits := io.in(i).bits
  }

  val oldest = VecInit(io.in.zip(eligible).map { case (in, allow) => in.valid && allow && in.bits.oldest })
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
      arb.io.in(i).ready && eligible(i))
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
    io.out(i).speculative := Mux1H(UIntToOH(arbs(i).io.chosen, n), io.in.map(_.speculative))
  }

  val bankOffset = log2Ceil(banks)

  for (i <- 0 until n) {
    require(banks == vParams.vrfBanking)
    val bank_sel = if (bankOffset == 0) 1.U else UIntToOH(vrfBank(io.in(i).req.bits.eg), banks)
    for (j <- 0 until banks) {
      arbs(j).io.in(i).valid := io.in(i).req.valid && bank_sel(j)
      arbs(j).io.in(i).bits.eg := io.in(i).req.bits.eg >> bankOffset
      arbs(j).io.in(i).bits.oldest := io.in(i).req.bits.oldest
      arbs(j).io.speculative(i) := io.in(i).speculative
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
    maxDepth: Int,
    prefetchPorts: Int = 0,
    trackWrites: Boolean = false,
    scalarPorts: Int = 0
)(implicit p: Parameters) extends CoreBundle()(p) with HasVectorParams {
  val read = MixedVec(reads.map(rc => Vec(rc, Flipped(new VectorReadIO))))
  val mask_read = MixedVec(maskReads.map(rc => Vec(rc, Flipped(new VectorReadIO))))
  val pipe_write_reqs = Vec(pipeWrites, Flipped(new VectorPipeWriteReqIO(maxDepth)))

  val pipe_writes = Vec(pipeWrites, Input(Valid(new VectorWrite(dLen))))
  val ll_writes = Vec(llWrites, Flipped(Decoupled(new VectorWrite(dLen))))
  val llOwners = Option.when(trackWrites)(Input(Vec(llWrites, Valid(UInt(vParams.vatSz.W)))))
  val pendingWrites = Option.when(trackWrites)(Output(Vec(vParams.vrfBanking, Valid(new VectorWriteHazard))))
  val writeCommits = Option.when(trackWrites)(Output(Vec(vParams.vrfBanking, Valid(new VectorWriteHazard))))
  val debug = Option.when(vParams.debugVrf)(Output(Vec(egsTotal, UInt(dLen.W))))
  val prefetch = Input(Vec(prefetchPorts, Valid(new VectorReadReq)))
  val e0Read = Input(Vec(scalarPorts, Bool()))
  val perf = Output(new VrfPerf)
}

class VrfPerf extends Bundle {
  val bankConflicts = UInt(64.W)
  val writeBlocksRead = UInt(64.W)
  val operandWait = UInt(64.W)
  val prefetches = UInt(64.W)
  val readGrantWait = UInt(64.W)
  val readResponseWait = UInt(64.W)
  val scalarForwards = UInt(64.W)
  val demandPrefetchWait = UInt(64.W)
  val prefetchDeferred = UInt(64.W)
  val prefetchUsed = UInt(64.W)
  val prefetchDiscarded = UInt(64.W)
}

class RegisterFile(reads: Seq[Int], maskReads: Seq[Int], pipeWrites: Int, llWrites: Int, maxDepth: Int,
                   sramSuffix: String = "evu_vrf", prefetchPorts: Seq[(Int, Int)] = Seq.empty,
                   trackWrites: Boolean = false, scalarPorts: Seq[(Int, Int)] = Seq.empty)
                  (implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {

  val io = IO(new RegisterFileIO(reads, maskReads, pipeWrites, llWrites, maxDepth,
    prefetchPorts.size, trackWrites, scalarPorts.size))
  val vrf = Module(new AraRegisterFile(reads, maskReads, pipeWrites, llWrites, maxDepth,
    sramSuffix, trackWrites, scalarPorts.nonEmpty))
  io.pipe_write_reqs <> vrf.io.pipe_write_reqs
  io.pipe_writes <> vrf.io.pipe_writes
  io.ll_writes <> vrf.io.ll_writes
  io.llOwners.foreach(vrf.io.llOwners.get := _)
  io.pendingWrites.foreach(_ := vrf.io.pendingWrites.get)
  io.writeCommits.foreach(_ := vrf.io.writeCommits.get)
  io.debug.foreach(_ := vrf.io.debug.get)
  val collectors = scala.collection.mutable.ArrayBuffer.empty[RegisterReadCollector]
  val scalarHits = scala.collection.mutable.ArrayBuffer.empty[Bool]

  private def collect(client: VectorReadIO, memory: SramReadIO, prefetchIndex: Int = -1,
      scalarIndex: Int = -1): Unit = {
    val collector = Module(new RegisterReadCollector)
    collectors += collector
    val scalarHit = WireInit(false.B)
    val scalarData = WireInit(0.U(32.W))
    if (scalarIndex >= 0) {
      val reg = client.req.bits.eg >> log2Ceil(egsPerVReg)
      val elementZero = (client.req.bits.eg & (egsPerVReg - 1).U) === 0.U
      val value = vrf.io.e0Values.get(reg)
      scalarHit := io.e0Read(scalarIndex) && elementZero && value.valid && !reset.asBool
      scalarData := value.bits
      scalarHits += (client.req.fire && scalarHit)
    }
    collector.io.client.req.valid := client.req.valid && !scalarHit
    collector.io.client.req.bits := client.req.bits
    client.req.ready := Mux(scalarHit, true.B, collector.io.client.req.ready)
    client.resp := Mux(scalarHit, scalarData.pad(dLen), collector.io.client.resp)
    collector.io.memory <> memory
    collector.io.prefetch := 0.U.asTypeOf(collector.io.prefetch)
    if (prefetchIndex >= 0) { collector.io.prefetch := io.prefetch(prefetchIndex) }
  }
  for (group <- reads.indices; port <- 0 until reads(group)) {
    collect(io.read(group)(port), vrf.io.read(group)(port),
      prefetchPorts.indexOf((group, port)), scalarPorts.indexOf((group, port)))
  }
  for ((clients, ports) <- io.mask_read.zip(vrf.io.mask_read); (client, port) <- clients.zip(ports)) {
    collect(client, port)
  }
  io.perf := vrf.io.perf
  val operandWait = RegInit(0.U(64.W))
  val prefetches = RegInit(0.U(64.W))
  when (collectors.map(c => c.io.client.req.valid && !c.io.client.req.ready).reduce(_ || _)) {
    operandWait := operandWait + 1.U
  }
  prefetches := prefetches + PopCount(VecInit(collectors.map(_.io.prefetched).toSeq))
  io.perf.operandWait := operandWait
  io.perf.prefetches := prefetches
  val grantWait = RegInit(0.U(64.W))
  val responseWait = RegInit(0.U(64.W))
  val scalarForwards = RegInit(0.U(64.W))
  when(collectors.map(_.io.grantWait).reduce(_ || _)) { grantWait := grantWait + 1.U }
  when(collectors.map(_.io.responseWait).reduce(_ || _)) { responseWait := responseWait + 1.U }
  scalarForwards := scalarForwards + PopCount(VecInit(scalarHits.toSeq :+ false.B))
  io.perf.readGrantWait := grantWait
  io.perf.readResponseWait := responseWait
  io.perf.scalarForwards := scalarForwards
  def diagnosticCount(name: String, increment: UInt): UInt = {
    if (vParams.diagnosticCounters) {
      val counter = RegInit(0.U(64.W)).suggestName(name)
      counter := counter + increment
      counter
    } else 0.U(64.W)
  }
  io.perf.demandPrefetchWait := diagnosticCount("demandPrefetchWait",
    (vrf.io.demandPrefetchBlocked || collectors.map(_.io.demandPrefetchWait).reduce(_ || _)).asUInt)
  io.perf.prefetchDeferred := diagnosticCount("prefetchDeferred", vrf.io.prefetchDeferred.asUInt)
  io.perf.prefetchUsed := diagnosticCount("prefetchUsed", PopCount(VecInit(collectors.map(_.io.prefetchUsed).toSeq)))
  io.perf.prefetchDiscarded := diagnosticCount("prefetchDiscarded",
    PopCount(VecInit(collectors.map(_.io.prefetchDiscarded).toSeq)))
}

class SramRegisterFileIO(
    reads: Seq[Int],
    maskReads: Seq[Int],
    pipeWrites: Int,
    llWrites: Int,
    maxDepth: Int,
    trackWrites: Boolean = false,
    scalarForwarding: Boolean = false
)(implicit p: Parameters) extends CoreBundle()(p) with HasVectorParams {
  val read = MixedVec(reads.map(rc => Vec(rc, Flipped(new SramReadIO))))
  val mask_read = MixedVec(maskReads.map(rc => Vec(rc, Flipped(new SramReadIO))))
  val pipe_write_reqs = Vec(pipeWrites, Flipped(new VectorPipeWriteReqIO(maxDepth)))
  val pipe_writes = Vec(pipeWrites, Input(Valid(new VectorWrite(dLen))))
  val ll_writes = Vec(llWrites, Flipped(Decoupled(new VectorWrite(dLen))))
  val llOwners = Option.when(trackWrites)(Input(Vec(llWrites, Valid(UInt(vParams.vatSz.W)))))
  val pendingWrites = Option.when(trackWrites)(Output(Vec(vParams.vrfBanking, Valid(new VectorWriteHazard))))
  val writeCommits = Option.when(trackWrites)(Output(Vec(vParams.vrfBanking, Valid(new VectorWriteHazard))))
  val debug = Option.when(vParams.debugVrf)(Output(Vec(egsTotal, UInt(dLen.W))))
  val perf = Output(new VrfPerf)
  val demandPrefetchBlocked = Output(Bool())
  val prefetchDeferred = Output(Bool())
  val e0Values = Option.when(scalarForwarding)(Output(Vec(32, Valid(UInt(32.W)))))
}

// Each element group spans 64-bit lanes; bank count comes from the VRF configuration.
class AraRegisterFile(
    reads: Seq[Int],
    maskReads: Seq[Int],
    pipeWrites: Int,
    llWrites: Int,
    maxDepth: Int,
    sramSuffix: String = "evu_vrf",
    trackWrites: Boolean = false,
    scalarForwarding: Boolean = false
)(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {

  private val sliceBits = 64
  private val sliceBytes = sliceBits / 8
  private val laneCount = dLen / sliceBits
  private val banksPerLane = vParams.vrfBanking
  private val bankBits = log2Ceil(banksPerLane)
  private val rowsPerBank = egsTotal / banksPerLane
  private val rowBits = log2Ceil(rowsPerBank max 2)

  require(dLen % sliceBits == 0, "Ara-style EVU VRF requires dLen to be a multiple of 64")
  require(banksPerLane >= 2 && isPow2(banksPerLane), "EVU VRF bank count must be a power of two")
  require(egsTotal % banksPerLane == 0, "EVU element groups must divide evenly across VRF banks")

  val io = IO(new SramRegisterFileIO(reads, maskReads, pipeWrites, llWrites, maxDepth,
    trackWrites, scalarForwarding))
  private val scalarResults = Option.when(scalarForwarding) {
    Module(new ScalarResultForwarding(32, banksPerLane))
  }
  io.e0Values.foreach(_ := scalarResults.get.io.values)

  private val storage = Seq.tabulate(laneCount, banksPerLane) { (lane, bank) =>
    Module(new SRAMTemplate(UInt(8.W), rowsPerBank, way = sliceBytes,
      singlePort = true, suffix = Some(sramSuffix))).suggestName(s"storage_${lane}_${bank}")
  }

  // Debug snapshots use a shadow copy to avoid adding memory read ports.
  private val debugShadow = Option.when(vParams.debugVrf) {
    RegInit(VecInit.fill(egsTotal)(0.U(dLen.W)))
  }

  private def bankOf(eg: UInt): UInt = vrfBank(eg)
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
  val bankConflict = WireInit(VecInit.fill(banksPerLane)(false.B))
  val writeBlock = WireInit(VecInit.fill(banksPerLane)(false.B))
  val demandPrefetchBlocked = WireInit(VecInit.fill(banksPerLane)(false.B))
  val prefetchDeferred = WireInit(VecInit.fill(banksPerLane)(false.B))

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
  private val llOwners = Option.when(trackWrites) {
    RegInit(VecInit.fill(banksPerLane)(0.U.asTypeOf(Valid(UInt(vParams.vatSz.W)))))
  }
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
      llOwners.foreach(_(bank) := io.llOwners.get(llArbiters(bank).io.chosen))
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
    val readPorts = allXbars.map(_(bank))
    val readReqs = readPorts.map(_.req)
    val ranks = readPorts.map(port => Cat(!port.speculative, port.req.bits.oldest))

    // Equal-row sharing must retain the strongest demand/age priority.
    for (requester <- 0 until requesterCount) {
      val req = readReqs(requester)
      val preferredPeer = (0 until requesterCount).filter(_ != requester).map { other =>
        readReqs(other).valid && readReqs(other).bits.eg === req.bits.eg &&
          (ranks(other) > ranks(requester) ||
            (ranks(other) === ranks(requester) && (other < requester).B))
      }.foldLeft(false.B)(_ || _)
      readArb.io.in(requester).valid := req.valid && !preferredPeer
      readArb.io.in(requester).bits := req.bits
      readArb.io.speculative(requester) := readPorts(requester).speculative

      req.ready := readArb.io.out.fire && readArb.io.out.bits.eg === req.bits.eg
    }

    val hasPipeWrite = pipeValid(bank)
    val hasLlWrite = llPendingValid(bank) && !hasPipeWrite
    val hasWrite = hasPipeWrite || hasLlWrite
    io.pendingWrites.foreach { pending =>
      pending(bank).valid := llPendingValid(bank) && llOwners.get(bank).valid && !reset.asBool
      pending(bank).bits.eg := llPending(bank).eg
      pending(bank).bits.vat := llOwners.get(bank).bits
      pending(bank).bits.mask := llPending(bank).mask
    }
    io.writeCommits.foreach { committed =>
      committed(bank) := io.pendingWrites.get(bank)
      committed(bank).valid := hasLlWrite && llOwners.get(bank).valid && !reset.asBool
    }
    val logicalReads = io.read.flatMap(_.toSeq) ++ io.mask_read.flatMap(_.toSeq)
    val contenders = logicalReads.map(r => r.req.valid && bankOf(r.req.bits.eg) === bank.U)
    val differentRows = logicalReads.indices.map { i =>
      (0 until i).map(j => contenders(i) && contenders(j) &&
        rowOf(logicalReads(i).req.bits.eg) =/= rowOf(logicalReads(j).req.bits.eg)).foldLeft(false.B)(_ || _)
    }.reduce(_ || _)
    bankConflict(bank) := differentRows && !hasWrite
    writeBlock(bank) := contenders.reduce(_ || _) && hasWrite
    val writeEg = Mux(hasPipeWrite, pipeEg(bank), llPending(bank).eg)
    // A bank accepts a read or a write per cycle; writes block all reads.
    readArb.io.out.ready := !hasWrite && !reset.asBool
    val selectedPrefetch = Mux1H(UIntToOH(readArb.io.chosen, requesterCount), readPorts.map(_.speculative))
    val waitingDemand = readPorts.map(port => port.req.valid && !port.speculative &&
      port.req.bits.eg =/= readArb.io.out.bits.eg).reduce(_ || _)
    val waitingPrefetch = readPorts.map(port => port.req.valid && port.speculative &&
      port.req.bits.eg =/= readArb.io.out.bits.eg).reduce(_ || _)
    demandPrefetchBlocked(bank) := readArb.io.out.fire && selectedPrefetch && waitingDemand
    prefetchDeferred(bank) := readArb.io.out.fire && !selectedPrefetch && waitingPrefetch
    assert(!demandPrefetchBlocked(bank), "VRF prefetch overtook a pending demand read")

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
    scalarResults.foreach { results =>
      val e0 = (writeBits.eg & (egsPerVReg - 1).U) === 0.U
      results.io.writes(bank).valid := writeValid && e0
      results.io.writes(bank).bits.register := writeBits.eg >> log2Ceil(egsPerVReg)
      results.io.writes(bank).bits.data := writeBits.data(31, 0)
      results.io.writes(bank).bits.mask := byteMask(3, 0)
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
      assert(!(sram.io.r.req.valid && sram.io.w.req.valid), "EVU VRF bank must remain 1RW")
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
  val conflicts = RegInit(0.U(64.W))
  val blocked = RegInit(0.U(64.W))
  when (bankConflict.asUInt.orR) { conflicts := conflicts + 1.U }
  when (writeBlock.asUInt.orR) { blocked := blocked + 1.U }
  io.perf.bankConflicts := conflicts
  io.perf.writeBlocksRead := blocked
  io.perf.operandWait := 0.U
  io.perf.prefetches := 0.U
  io.perf.readGrantWait := 0.U
  io.perf.readResponseWait := 0.U
  io.perf.scalarForwards := 0.U
  io.perf.demandPrefetchWait := 0.U
  io.perf.prefetchDeferred := 0.U
  io.perf.prefetchUsed := 0.U
  io.perf.prefetchDiscarded := 0.U
  io.demandPrefetchBlocked := demandPrefetchBlocked.asUInt.orR
  io.prefetchDeferred := prefetchDeferred.asUInt.orR
}
