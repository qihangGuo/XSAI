package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.{CoreModule, TileVisibilityNodeKey}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.ExecuteSequencer
import xiangshan.evu.common._
import xiangshan.evu.exu.SIMDFPFMAFactory

class EvuScalarSourceHarness(implicit p: Parameters) extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val dispatch = Input(Bool())
    val ready = Output(Bool())
    val source = Input(UInt(dLen.W))
    val issueReady = Input(Bool())
    val issue = Output(Bool())
    val sourceRead = Output(Bool())
    val sourceEg = Output(UInt(log2Ceil(egsTotal).W))
    val sourceData = Output(UInt(dLen.W))
    val index = Output(UInt(log2Ceil(maxVLMax).W))
    val prefetchSource = Output(Bool())
  })
  val factory = SIMDFPFMAFactory(4, true)
  val insns = VXSequencerParams("scalar", Seq(factory)).insns
  val seq = Module(new ExecuteSequencer(insns, 4, 1))
  val inst = WireInit(0.U.asTypeOf(new BackendIssueInst))
  inst.bits := (OPFFunct6.fadd.asUInt << 26) | (1.U << 25) | (2.U << 20) |
    (1.U << 15) | (VectorConsts.OPFVV << 12) | (3.U << 7) | VectorConsts.opcVector
  inst.vconfig.vtype.vsew := 2.U
  inst.vconfig.vl := 16.U
  inst.renv1 := true.B
  inst.renv2 := true.B
  inst.wvd := true.B
  inst.scalarVrfSource := true.B
  seq.io.dis.valid := io.dispatch
  seq.io.dis.bits := inst
  io.ready := seq.io.dis.ready
  seq.io.dis_stall := false.B
  seq.io.older_reads := 0.U
  seq.io.older_writes := 0.U
  seq.io.vat_head := 0.U
  seq.io.pipe_write_req.available := true.B
  seq.io.acc_valid := false.B
  seq.io.vgu.slide_req.ready := true.B
  seq.io.vgu.slide_data := 0.U
  seq.io.vgu.gather_eidx.valid := false.B
  seq.io.vgu.gather_eidx.bits := 0.U
  val reads = Seq(seq.io.rvs1, seq.io.rvs2, seq.io.rvd, seq.io.rvm)
  reads.foreach { r => r.req.ready := true.B; r.resp := io.source }
  seq.io.iss.ready := io.issueReady
  io.issue := seq.io.iss.valid
  io.sourceRead := seq.io.rvs1.req.fire
  io.sourceEg := seq.io.rvs1.req.bits.eg
  io.sourceData := seq.io.rvs1_data
  io.index := seq.io.iss.bits.eidx
  io.prefetchSource := seq.io.prefetch(0).valid
}

class EvuScalarSourceShell(implicit p: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("scalar-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuScalarSourceShellImp(this)
}

class EvuScalarSourceShellImp(outer: EvuScalarSourceShell)(implicit p: Parameters) extends LazyModuleImp(outer) {
  val harness = Module(new EvuScalarSourceHarness()(p.alterPartial { case TileVisibilityNodeKey => outer.client }))
  val io = IO(chiselTypeOf(harness.io))
  io <> harness.io
}

class EvuScalarSourceSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "VRF scalar operand capture"
  it should "read element zero once and retain it across subsequent groups and execution backpressure" in {
    implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 512, 256, 256, VectorParams.lgvParams)
    test(LazyModule(new EvuScalarSourceShell).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.source.poke(0x3f800000L.U)
      dut.io.issueReady.poke(true.B)
      dut.io.dispatch.poke(true.B)
      dut.io.ready.expect(true.B)
      dut.clock.step()
      dut.io.dispatch.poke(false.B)
      val expected = (0 until 8).map(i => BigInt(0x3f800000L) << (32 * i)).reduce(_ | _)
      dut.io.issue.expect(true.B)
      dut.io.sourceRead.expect(true.B)
      dut.io.sourceEg.expect(2.U)
      dut.io.sourceData.expect(expected.U)
      dut.io.index.expect(0.U)
      dut.io.prefetchSource.expect(false.B)
      dut.clock.step()
      dut.io.source.poke(0x7f800001L.U)
      dut.io.issueReady.poke(false.B)
      for (_ <- 0 until 4) {
        dut.io.sourceRead.expect(false.B)
        dut.io.sourceData.expect(expected.U)
        dut.io.index.expect(8.U)
        dut.clock.step()
      }
      dut.io.issueReady.poke(true.B)
      dut.io.issue.expect(true.B)
      dut.clock.step()
      dut.io.issue.expect(false.B)
    }
  }
}
