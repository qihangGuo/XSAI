package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import freechips.rocketchip.tile.{CoreModule, TileVisibilityNodeKey}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.{ExecuteSequencer, RegisterFile, SpecialSequencer}
import xiangshan.evu.common._
import xiangshan.evu.exu._

class EvuSaturnOperandHarness(special: Boolean, pipelinedReads: Boolean = false)(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val dispatch = Input(Bool())
    val dispatchReady = Output(Bool())
    val vl = Input(UInt((1 + log2Ceil(maxVLMax)).W))
    val olderWrites = Input(UInt(egsTotal.W))
    val ready = Input(Vec(4, Bool()))
    val resp = Input(Vec(4, UInt(dLen.W)))
    val req = Output(Vec(4, Bool()))
    val eg = Output(Vec(4, UInt(log2Ceil(egsTotal).W)))
    val data = Output(Vec(4, UInt(dLen.W)))
    val issue = Output(Bool())
    val issueReady = Input(Bool())
    val prefetch = Output(Vec(3, Valid(new VectorReadReq)))
    val issueIndex = Output(UInt(log2Ceil(maxVLMax).W))
    val writeBank = Output(UInt(vParams.vrfBanking.W))
    val writeRequest = Output(Bool())
  })
  val inst = WireInit(0.U.asTypeOf(new BackendIssueInst))
  inst.vconfig.vl := io.vl
  inst.vconfig.vtype.vsew := 2.U
  inst.renv2 := true.B
  inst.renvm := true.B
  io.req := VecInit.fill(4)(false.B)
  io.eg := VecInit.fill(4)(0.U(log2Ceil(egsTotal).W))
  io.data := VecInit.fill(4)(0.U(dLen.W))
  io.prefetch := 0.U.asTypeOf(io.prefetch)
  io.issueIndex := 0.U
  io.writeBank := 0.U
  io.writeRequest := false.B
  if (special) {
    val seq = Module(new SpecialSequencer(IntegerPipeFactory.insns ++ PermuteUnitFactory.insns))
    // Masked indexed load: index vector v8 and mask v0 both select bank 0.
    inst.bits := ((BigInt(8) << 20) | (BigInt(6) << 12) | 7).U(32.W)
    inst.mop := 1.U
    seq.io.dis.valid := io.dispatch
    io.dispatchReady := seq.io.dis.ready
    seq.io.dis.bits := inst
    seq.io.dis_stall := false.B
    seq.io.older_writes := io.olderWrites
    seq.io.older_reads := 0.U
    seq.io.vat_head := 0.U
    seq.io.iss.ready := io.issueReady
    seq.io.rvs2.ready := io.ready(0)
    seq.io.rvm.ready := io.ready(1)
    seq.io.acc_init_resp := io.resp(0)
    seq.io.rvm_resp := io.resp(1)
    seq.io.acc_slot := 0.U
    seq.io.acc_other_slot := 0.U
    seq.io.acc_merge := false.B
    seq.io.acc_done := false.B
    seq.io.acc_fu_resp.valid := false.B
    seq.io.acc_fu_resp.bits := 0.U.asTypeOf(seq.io.acc_fu_resp.bits)
    seq.io.acc_fu_resp_slot := 0.U
    seq.io.acc_data.ready := false.B
    io.req(0) := seq.io.rvs2.valid
    io.req(1) := seq.io.rvm.valid
    io.eg(0) := seq.io.rvs2.bits.eg
    io.eg(1) := seq.io.rvm.bits.eg
    io.data(0) := seq.io.rvs2_data
    io.data(1) := seq.io.rvm_data
    io.issue := seq.io.iss.valid
  } else {
    val insns = VXSequencerParams("mul", Seq(IntegerMultiplyFactory(3, true))).insns
    val seq = Module(new ExecuteSequencer(insns, 3, 1))
    val regs = if (pipelinedReads) Seq(1, 2, 3) else Seq(4, 8, 12)
    inst.bits := (OPMFunct6.macc.asUInt << 26) | ((if (pipelinedReads) 1.U else 0.U) << 25) |
      (regs(0).U << 15) | (regs(1).U << 20) |
      (regs(2).U << 7) | (VectorConsts.OPMVV << 12) | VectorConsts.opcVector
    inst.renvm := (!pipelinedReads).B
    inst.renv1 := true.B
    inst.renvd := true.B
    inst.wvd := pipelinedReads.B
    seq.io.dis.valid := io.dispatch
    io.dispatchReady := seq.io.dis.ready
    seq.io.dis.bits := inst
    seq.io.dis_stall := false.B
    seq.io.older_writes := io.olderWrites
    seq.io.older_reads := 0.U
    seq.io.vat_head := 0.U
    seq.io.iss.ready := io.issueReady
    seq.io.pipe_write_req.available := true.B
    seq.io.acc_valid := false.B
    seq.io.vgu.slide_req.ready := true.B
    seq.io.vgu.slide_data := 0.U
    seq.io.vgu.gather_eidx.valid := false.B
    seq.io.vgu.gather_eidx.bits := 0.U
    val reads = Seq(seq.io.rvs1, seq.io.rvs2, seq.io.rvd, seq.io.rvm)
    for (i <- 0 until 4) {
      reads(i).req.ready := io.ready(i)
      reads(i).resp := io.resp(i)
      io.req(i) := reads(i).req.valid
      io.eg(i) := reads(i).req.bits.eg
    }
    io.data := VecInit(seq.io.rvs1_data, seq.io.rvs2_data, seq.io.rvd_data, seq.io.rvm_data)
    io.issue := seq.io.iss.valid
    io.prefetch := seq.io.prefetch
    io.issueIndex := seq.io.iss.bits.eidx
    io.writeBank := seq.io.pipe_write_req.bank_sel
    io.writeRequest := seq.io.pipe_write_req.request
  }
}

class EvuSaturnOperandShell(val special: Boolean, val withVrf: Boolean = false,
    val pipelinedReads: Boolean = false)(implicit p: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("operand-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuSaturnOperandShellImp(this)
}

class EvuSaturnOperandShellImp(outer: EvuSaturnOperandShell)(implicit p: Parameters) extends LazyModuleImp(outer) {
  val harness = Module(new EvuSaturnOperandHarness(outer.special, outer.pipelinedReads)(p.alterPartial {
    case TileVisibilityNodeKey => outer.client
  }))
  val io = IO(chiselTypeOf(harness.io))
  io <> harness.io
  val vrfInitialized = IO(Output(Bool()))
  vrfInitialized := true.B
  if (outer.withVrf) {
    val suffix = if (outer.pipelinedReads) "vrf_saturn_prefetch" else "vrf_saturn_operands"
    val vrf = Module(new RegisterFile(Seq(4), Seq(1), 1, 1, 4, suffix,
      Seq((0, 0), (0, 1), (0, 2))))
    val initialized = RegInit(false.B)
    val initIndex = RegInit(0.U(3.W))
    val addresses = if (outer.pipelinedReads) Seq(2, 3, 4, 5, 6, 7, 0) else Seq(8, 9, 16, 17, 24, 25, 0)
    val eg = VecInit(addresses.map(_.U(6.W)))(initIndex)
    vrfInitialized := initialized
    vrf.io.pipe_writes(0).valid := !initialized
    vrf.io.pipe_writes(0).bits.eg := eg
    vrf.io.pipe_writes(0).bits.data := 0x100.U + eg
    vrf.io.pipe_writes(0).bits.mask := ~0.U(256.W)
    when (!initialized) {
      initIndex := initIndex + 1.U
      when (initIndex === 6.U) { initialized := true.B }
    }
    vrf.io.ll_writes(0).valid := false.B
    vrf.io.ll_writes(0).bits := 0.U.asTypeOf(vrf.io.ll_writes(0).bits)
    vrf.io.pipe_write_reqs(0).request := false.B
    vrf.io.pipe_write_reqs(0).fire := false.B
    vrf.io.pipe_write_reqs(0).bank_sel := 0.U
    vrf.io.pipe_write_reqs(0).pipe_depth := 0.U
    vrf.io.pipe_write_reqs(0).oldest := false.B
    vrf.io.mask_read(0)(0).req.valid := false.B
    vrf.io.mask_read(0)(0).req.bits := 0.U.asTypeOf(vrf.io.mask_read(0)(0).req.bits)
    vrf.io.prefetch := harness.io.prefetch
    for (i <- 0 until 4) {
      val read = vrf.io.read(0)(i)
      read.req.valid := harness.io.req(i) && initialized
      read.req.bits.eg := harness.io.eg(i)
      read.req.bits.oldest := true.B
      harness.io.ready(i) := read.req.ready && initialized
      harness.io.resp(i) := read.resp
    }
  }
  val bus = outer.client.out.head._1
  bus.a.valid := false.B
  bus.a.bits := 0.U.asTypeOf(bus.a.bits)
  bus.d.ready := true.B
  val manager = outer.sink.in.head._1
  manager.a.ready := true.B
  manager.d.valid := false.B
  manager.d.bits := 0.U.asTypeOf(manager.d.bits)
}

class EvuSaturnOperandSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Saturn operand collectors"
  it should "prefetch real VRF operands across chunks without bypassing older writes" in {
    implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 512, 256, 128, VectorParams.lgvParams)
    test(LazyModule(new EvuSaturnOperandShell(false, withVrf = true, pipelinedReads = true)).module)
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.dispatch.poke(false.B)
        dut.io.vl.poke(16.U)
        dut.io.olderWrites.poke(0.U)
        dut.io.issueReady.poke(true.B)
        dut.io.ready.foreach(_.poke(false.B))
        dut.io.resp.foreach(_.poke(0.U))
        dut.clock.step(8)
        dut.vrfInitialized.expect(true.B)
        def dispatch(): Unit = {
          dut.io.dispatch.poke(true.B)
          dut.io.dispatchReady.expect(true.B)
          dut.clock.step()
          dut.io.dispatch.poke(false.B)
        }
        def awaitIssue(): Unit = {
          var n = 0
          while (!dut.io.issue.peek().litToBoolean && n < 20) { dut.clock.step(); n += 1 }
          dut.io.issue.expect(true.B)
        }
        def check(group: Int): Unit = {
          dut.io.issueIndex.expect((group * 8).U)
          for (i <- 0 until 3) dut.io.data(i).expect((0x102 + 2 * i + group).U)
          val eg = 6 + group
          val bank = (eg & 7) ^ ((eg >> 1) & 7)
          dut.io.writeBank.expect((1 << bank).U)
        }
        dispatch()
        awaitIssue()
        check(0)
        dut.io.prefetch.foreach(_.valid.expect(true.B))
        dut.clock.step()
        // Successor requests launch on the current issue edge. The synchronous
        // response can feed the very next issue, or be captured during a stall.
        dut.io.issue.expect(true.B)
        check(1)
        dut.io.issueReady.poke(false.B)
        dut.clock.step(3)
        dut.io.issue.expect(true.B)
        check(1)
        dut.io.issueReady.poke(true.B)
        dut.clock.step()
        dut.io.issue.expect(false.B)

        dispatch()
        awaitIssue()
        // Only the next v1 chunk is pending from an older writer. It must not
        // be speculatively cached, while independent successor operands may be.
        dut.io.olderWrites.poke((BigInt(1) << 3).U)
        dut.io.prefetch(0).valid.expect(false.B)
        dut.clock.step()
        dut.io.issue.expect(false.B)
        dut.io.req.foreach(_.expect(false.B))
        dut.clock.step(2)
        dut.io.olderWrites.poke(0.U)
        awaitIssue()
        check(1)
        dut.clock.step()
        dut.io.issue.expect(false.B)
      }
  }

  it should "collect same-bank operands from the synchronous physical VRF before issue" in {
    implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 512, 256, 128, VectorParams.lgvParams)
    test(LazyModule(new EvuSaturnOperandShell(false, withVrf = true)).module)
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.dispatch.poke(false.B)
        dut.io.vl.poke(8.U)
        dut.io.olderWrites.poke(0.U)
        dut.io.issueReady.poke(false.B)
        dut.io.ready.foreach(_.poke(false.B))
        dut.io.resp.foreach(_.poke(0.U))
        dut.clock.step(8)
        dut.vrfInitialized.expect(true.B)
        dut.io.dispatch.poke(true.B)
        dut.clock.step()
        dut.io.dispatch.poke(false.B)
        dut.io.issue.expect(false.B)
        dut.clock.step()
        var cycles = 0
        while (!dut.io.issue.peek().litToBoolean && cycles < 16) {
          dut.clock.step()
          cycles += 1
        }
        dut.io.issue.expect(true.B)
        for ((value, i) <- Seq(0x108, 0x110, 0x118, 0x100).zipWithIndex) {
          dut.io.data(i).expect(value.U)
        }
        // The final response can issue before it has been captured.
        dut.clock.step()
        dut.io.req.foreach(_.expect(false.B))
        dut.clock.step(2)
        dut.io.issue.expect(true.B)
        dut.io.issueReady.poke(true.B)
        dut.clock.step()
        dut.io.issue.expect(false.B)
      }
  }
  for (special <- Seq(false, true)) {
    it should s"wait for older writes and collect conflicting reads independently (special=$special)" in {
      val base = Parameters.empty.alterPartial {
        case freechips.rocketchip.tile.XLen => 64
        case freechips.rocketchip.tile.MaxHartIdBits => 1
        case freechips.rocketchip.rocket.PgLevels => 3
      }
      implicit val p: Parameters = EvuVectorParameters(base, 512, 256, 128,
        VectorParams.lgvParams)
      test(LazyModule(new EvuSaturnOperandShell(special)).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.dispatch.poke(false.B)
        dut.io.vl.poke(8.U)
        dut.io.olderWrites.poke(((BigInt(1) << 64)-1).U)
        dut.io.issueReady.poke(false.B)
        dut.io.ready.foreach(_.poke(true.B))
        dut.io.resp.foreach(_.poke(0xdead.U))
        dut.io.dispatch.poke(true.B)
        dut.clock.step()
        dut.io.dispatch.poke(false.B)
        dut.clock.step(3)
        dut.io.req.foreach(_.expect(false.B))
        dut.io.issue.expect(false.B)
        dut.io.writeRequest.expect(false.B)
        dut.io.ready.foreach(_.poke(false.B))
        dut.io.olderWrites.poke(0.U)
        val ports = if (special) Seq(1, 0) else Seq(3, 2, 0, 1)
        for ((port, index) <- ports.zipWithIndex) {
          dut.io.issue.expect(false.B)
          dut.io.writeRequest.expect(false.B)
          dut.io.req(port).expect(true.B)
          dut.io.resp(port).poke((0x100 + port).U)
          dut.io.ready(port).poke(true.B)
          dut.clock.step()
          dut.io.ready(port).poke(false.B)
          dut.io.resp(port).poke(0.U)
          dut.io.req(port).expect(false.B)
          dut.io.data(port).expect((0x100 + port).U)
          dut.io.issue.expect((index == ports.size-1).B)
          dut.io.writeRequest.expect((!special && index == ports.size-1).B)
        }
        dut.clock.step(3)
        for (port <- ports) dut.io.data(port).expect((0x100 + port).U)
        dut.io.issue.expect(true.B)
        dut.io.issueReady.poke(true.B)
        dut.clock.step()
        dut.io.issue.expect(false.B)
      }
    }
  }

  it should "issue on the final operand response and clear captures across groups and dispatches" in {
    implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 512, 256, 128, VectorParams.lgvParams)
    test(LazyModule(new EvuSaturnOperandShell(false)).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.dispatch.poke(false.B)
      dut.io.vl.poke(16.U)
      dut.io.olderWrites.poke(0.U)
      dut.io.issueReady.poke(true.B)
      dut.io.ready.foreach(_.poke(false.B))
      dut.io.resp.foreach(_.poke(0.U))
      dut.io.dispatch.poke(true.B)
      dut.clock.step()
      dut.io.dispatch.poke(false.B)
      dut.io.req.foreach(_.expect(true.B))
      dut.io.issue.expect(false.B)
      dut.io.ready(2).poke(true.B)
      dut.io.resp(2).poke(0x202.U)
      dut.io.issue.expect(false.B)
      dut.clock.step()
      dut.io.ready(2).poke(false.B)
      dut.io.req(2).expect(false.B)
      for (port <- Seq(0, 1, 3)) {
        dut.io.ready(port).poke(true.B)
        dut.io.resp(port).poke((0x200 + port).U)
      }
      dut.io.issue.expect(true.B)
      for (port <- 0 until 4) dut.io.data(port).expect((0x200 + port).U)
      dut.clock.step()
      dut.io.ready.foreach(_.poke(false.B))
      dut.io.issue.expect(false.B)
      dut.io.req.foreach(_.expect(true.B))
      for (port <- 0 until 4) {
        dut.io.ready(port).poke(true.B)
        dut.io.resp(port).poke((0x300 + port).U)
      }
      dut.io.issue.expect(true.B)
      // Replace the instruction on the same edge as its final group issues.
      dut.io.dispatch.poke(true.B)
      dut.io.dispatchReady.expect(true.B)
      dut.clock.step()
      dut.io.dispatch.poke(false.B)
      dut.io.ready.foreach(_.poke(false.B))
      dut.io.issue.expect(false.B)
      dut.io.req.foreach(_.expect(true.B))
      for (group <- 0 until 2) {
        for (port <- 0 until 4) {
          dut.io.ready(port).poke(true.B)
          dut.io.resp(port).poke((0x400 + 0x100 * group + port).U)
        }
        dut.io.issue.expect(true.B)
        for (port <- 0 until 4) dut.io.data(port).expect((0x400 + 0x100 * group + port).U)
        dut.clock.step()
        dut.io.ready.foreach(_.poke(false.B))
        dut.io.issue.expect(false.B)
      }
      dut.io.req.foreach(_.expect(false.B))
    }
  }
}
