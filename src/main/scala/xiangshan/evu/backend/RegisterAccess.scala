package xiangshan.evu.backend

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tile.{CoreModule}
import freechips.rocketchip.util._
import xiangshan.evu.common._

class RegisterAccess(exSeqs: Int, maxExuDepth: Int, reductionContexts: Int = 1,
    executionClasses: Seq[Int] = Seq.empty, shortIntegerContexts: Set[Int] = Set.empty)(implicit p: Parameters)
    extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val vls = new Bundle {
      val rvm = Flipped(new VectorReadIO)
    }
    val vss = new Bundle {
      val rvd = Flipped(new VectorReadIO)
      val rvm = Flipped(new VectorReadIO)
      val e0Read = Input(Bool())
      val bypass = Input(Bool())
    }
    val vxs = Vec(exSeqs, new Bundle {
      val rvs1 = Flipped(new VectorReadIO)
      val rvs2 = Flipped(new VectorReadIO)
      val rvd = Flipped(new VectorReadIO)
      val rvm = Flipped(new VectorReadIO)
      val pipe_write_req = Flipped(new VectorPipeWriteReqIO(maxExuDepth))
      val prefetch = Input(Vec(3, Valid(new VectorReadReq)))
      val e0Read = Input(Vec(3, Bool()))
      val bypass = Input(Vec(3, Bool()))
    })
    val vps = Vec(reductionContexts, new Bundle {
      val rvs2 = Flipped(new VectorReadIO)
      val rvm = Flipped(new VectorReadIO)
      val e0Read = Input(Bool())
    })

    val frontend = new Bundle {
      val rindex = Flipped(new VectorReadIO)
      val rmask = Flipped(new VectorReadIO)
    }

    val pipe_writes = Vec(exSeqs, Input(Valid(new VectorWrite(dLen))))
    val iter_writes = Vec(exSeqs, Flipped(Decoupled(new VectorWrite(dLen))))
    val load_write = Flipped(Decoupled(new VectorWrite(dLen)))
    val loadOwner = Input(UInt(vParams.vatSz.W))
    val iterOwners = Input(Vec(exSeqs, UInt(vParams.vatSz.W)))
    val pendingWrites = Output(Vec(vParams.vrfBanking, Valid(new VectorWriteHazard)))
    val pendingLoads = Output(Vec(vParams.vrfBanking, Valid(new VectorForwardResult)))
    val writeInvalidations = Output(Vec(vParams.vrfBanking, Valid(UInt(log2Ceil(egsTotal).W))))
    val debug = Option.when(vParams.debugVrf)(Output(Vec(egsTotal, UInt(dLen.W))))
    val perf = Output(new VrfPerf)
    val rs1Events = Option.when(vParams.diagnosticCounters)(Output(Vec(exSeqs, new VrfReadObservation(log2Ceil(egsTotal)))))
  })

  // The execute path has a logical 3R1W operand shape.  RegisterFile maps
  // these logical requests onto the Ara-style 1RW banks when enabled.
  //  read0 arbitrates between <- [vxs-rs1], vmu-index
  //  read1 arbitrates between <- [vxs-rs2], frontend-index
  //  read2 arbitrates between <- [vxs-rs3], vss-vrd

  // Mask read arbitrates between <- [vxs], vls, vss, vps, frontend-mask

  require(executionClasses.isEmpty || executionClasses.size == exSeqs)
  private val exClasses = if (executionClasses.isEmpty) Seq.fill(exSeqs)(4) else executionClasses
  // Must follow RegisterFile's data-group order, then its mask-group order.
  private val readClasses = exClasses ++ Seq.fill(reductionContexts)(4) ++
    exClasses ++ Seq(4) ++ exClasses ++ Seq(3) ++
    exClasses ++ Seq(4, 3) ++ Seq.fill(reductionContexts)(4) ++ Seq(4)
  val vrf = Module(new RegisterFile(
    reads = Seq(reductionContexts + exSeqs, 1 + exSeqs, 1 + exSeqs),
    maskReads = Seq(3 + reductionContexts + exSeqs),
    pipeWrites = exSeqs,
    llWrites = exSeqs + 2, // load + reset
    maxExuDepth,
    prefetchPorts = (0 until exSeqs).flatMap(i => (0 until 3).map(group => (group, i))),
    trackWrites = true,
    scalarPorts = ((0 until exSeqs).flatMap(i => (0 until 3).map(group => (group, i))) :+ (2, exSeqs)) ++
      (0 until reductionContexts).map(i => (0, exSeqs + i)),
    readClasses = readClasses,
    bypassPorts = (0 until exSeqs).flatMap(i => (0 until 3).map(group => (group, i))) :+ (2, exSeqs),
    unusedReads = shortIntegerContexts.map(i => (2, i)),
    unusedMaskReads = shortIntegerContexts.map(i => (0, i))
  ))
  val resetting = RegInit(true.B)
  io.perf := vrf.io.perf
  io.rs1Events.foreach(_.zip(vrf.io.rs1Events.get.take(exSeqs)).foreach { case (out, event) => out := event })
  io.pendingWrites := vrf.io.pendingWrites.get
  io.pendingLoads := vrf.io.pendingLoads.get
  io.writeInvalidations := vrf.io.writeInvalidations
  vrf.io.llOwners.get(0).valid := false.B
  vrf.io.llOwners.get(0).bits := 0.U
  vrf.io.llOwners.get(1).valid := true.B
  vrf.io.llOwners.get(1).bits := io.loadOwner
  for (i <- 0 until exSeqs) {
    vrf.io.llOwners.get(2 + i).valid := true.B
    vrf.io.llOwners.get(2 + i).bits := io.iterOwners(i)
  }
  for (i <- 0 until exSeqs; port <- 0 until 3) {
    vrf.io.prefetch(i * 3 + port) := io.vxs(i).prefetch(port)
    vrf.io.e0Read(i * 3 + port) := io.vxs(i).e0Read(port) && !resetting
    vrf.io.bypass(i * 3 + port) := io.vxs(i).bypass(port) && !resetting
    when (resetting) { vrf.io.prefetch(i * 3 + port).valid := false.B }
  }
  vrf.io.e0Read(exSeqs * 3) := io.vss.e0Read && !resetting
  vrf.io.bypass(exSeqs * 3) := io.vss.bypass && !resetting
  for (i <- 0 until reductionContexts) {
    vrf.io.e0Read(exSeqs * 3 + 1 + i) := io.vps(i).e0Read && !resetting
  }

  io.debug.foreach(_ := vrf.io.debug.get)
  val reset_ctr = RegInit(0.U(log2Ceil(egsTotal).W))

  // LL writes.  The reset write is a normal VRF transaction; advancing the
  // reset address before it fires would skip rows when a bank is busy.
  vrf.io.ll_writes(0).valid := resetting
  vrf.io.ll_writes(0).bits.eg := reset_ctr
  vrf.io.ll_writes(0).bits.data := 0.U
  vrf.io.ll_writes(0).bits.mask := ~(0.U(dLen.W))
  vrf.io.ll_writes(1).valid := io.load_write.valid && !resetting
  vrf.io.ll_writes(1).bits := io.load_write.bits
  io.load_write.ready := vrf.io.ll_writes(1).ready && !resetting
  for (i <- 0 until exSeqs) {
    vrf.io.ll_writes(2+i).valid := io.iter_writes(i).valid && !resetting
    vrf.io.ll_writes(2+i).bits := io.iter_writes(i).bits
    io.iter_writes(i).ready := vrf.io.ll_writes(2+i).ready && !resetting
  }

  // Pipe writes
  for (i <- 0 until exSeqs) {
    vrf.io.pipe_write_reqs(i) <> io.vxs(i).pipe_write_req
    vrf.io.pipe_writes(i).valid := io.pipe_writes(i).valid && !resetting
    vrf.io.pipe_writes(i).bits := io.pipe_writes(i).bits
  }

  // Reads
  for (i <- 0 until exSeqs) {
    vrf.io.read(0)(i) <> io.vxs(i).rvs1
    vrf.io.read(1)(i) <> io.vxs(i).rvs2
    vrf.io.read(2)(i) <> io.vxs(i).rvd
    vrf.io.mask_read(0)(i) <> io.vxs(i).rvm
  }

  for (i <- 0 until reductionContexts) {
    vrf.io.read(0)(exSeqs + i) <> io.vps(i).rvs2
    vrf.io.mask_read(0)(exSeqs + 2 + i) <> io.vps(i).rvm
  }
  vrf.io.read(1)(exSeqs) <> io.frontend.rindex
  vrf.io.read(2)(exSeqs) <> io.vss.rvd

  vrf.io.mask_read(0)(exSeqs)   <> io.vls.rvm
  vrf.io.mask_read(0)(exSeqs+1) <> io.vss.rvm
  vrf.io.mask_read(0)(exSeqs+2+reductionContexts) <> io.frontend.rmask

  when (resetting) {
    vrf.io.read.foreach(_.foreach(_.req.valid := false.B))
    vrf.io.mask_read.foreach(_.foreach(_.req.valid := false.B))
    io.vls.rvm.req.ready := false.B
    io.vss.rvd.req.ready := false.B
    io.vss.rvm.req.ready := false.B
    io.vxs.foreach(_.rvs1.req.ready := false.B)
    io.vxs.foreach(_.rvs2.req.ready := false.B)
    io.vxs.foreach(_.rvd.req.ready := false.B)
    io.vxs.foreach(_.rvm.req.ready := false.B)
    io.vps.foreach(_.rvs2.req.ready := false.B)
    io.vps.foreach(_.rvm.req.ready := false.B)
    io.frontend.rindex.req.ready := false.B
    io.frontend.rmask.req.ready := false.B
    io.load_write.ready := false.B
    io.iter_writes.foreach(_.ready := false.B)
    io.vxs.foreach(_.pipe_write_req.available := false.B)
    // Pipe writes have no ready channel; their valid bits are gated above.
    when (vrf.io.ll_writes(0).fire) {
      when (reset_ctr === (egsTotal - 1).U) {
        resetting := false.B
      } .otherwise {
        reset_ctr := reset_ctr + 1.U
      }
    }
  }


}
