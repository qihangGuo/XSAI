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
import xiangshan.evu.backend.{ExecuteSequencer, ExecuteWaitSignals}
import xiangshan.evu.common._
import xiangshan.evu.exu.SIMDFPFMAFactory

class EvuIndependentOperandHarness(implicit p: Parameters) extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new BackendIssueInst))
    val olderWrites = Input(UInt(egsTotal.W))
    val olderReads = Input(UInt(egsTotal.W))
    val readReady = Input(Vec(4, Bool()))
    val readData = Input(Vec(4, UInt(dLen.W)))
    val readValid = Output(Vec(4, Bool()))
    val data = Output(Vec(4, UInt(dLen.W)))
    val feedback = Input(Bool())
    val issueReady = Input(Bool())
    val writebackReady = Input(Bool())
    val issue = Output(Bool())
    val index = Output(UInt(log2Ceil(maxVLMax).W))
    val prefetch = Output(Vec(3, Valid(new VectorReadReq)))
    val early = Output(UInt(3.W))
    val waitReason = Output(new ExecuteWaitSignals)
  })
  val insns = VXSequencerParams("stream-test", Seq(SIMDFPFMAFactory(4, true))).insns
  val seq = Module(new ExecuteSequencer(insns, 4, 1))
  seq.io.dis <> io.command
  seq.io.dis_stall := false.B
  seq.io.older_writes := io.olderWrites
  seq.io.older_reads := io.olderReads
  seq.io.vat_head := 0.U
  seq.io.acc_valid := io.feedback
  seq.io.iss.ready := io.issueReady
  seq.io.pipe_write_req.available := io.writebackReady
  seq.io.vgu.slide_req.ready := true.B
  seq.io.vgu.slide_data := 0.U
  seq.io.vgu.gather_eidx.valid := false.B
  seq.io.vgu.gather_eidx.bits := 0.U
  for ((read, i) <- Seq(seq.io.rvs1, seq.io.rvs2, seq.io.rvd, seq.io.rvm).zipWithIndex) {
    read.req.ready := io.readReady(i)
    read.resp := io.readData(i)
    io.readValid(i) := read.req.valid
  }
  io.data := VecInit(seq.io.rvs1_data, seq.io.rvs2_data, seq.io.rvd_data, seq.io.rvm_data)
  io.issue := seq.io.iss.valid
  io.index := seq.io.iss.bits.eidx
  io.prefetch := seq.io.prefetch
  io.early := seq.io.earlyOperandReads
  io.waitReason := seq.io.waitReason
}

class EvuIndependentOperandShell(implicit p: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("operand-stream-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuIndependentOperandShellImp(this)
}

class EvuIndependentOperandShellImp(outer: EvuIndependentOperandShell)(implicit p: Parameters)
    extends LazyModuleImp(outer) {
  val harness = Module(new EvuIndependentOperandHarness()(p.alterPartial {
    case TileVisibilityNodeKey => outer.client
  }))
  val io = IO(chiselTypeOf(harness.io))
  io <> harness.io
}

class EvuIndependentOperandSpec extends AnyFlatSpec with ChiselScalatestTester {
  private def parameters: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }, 1024, 256, 256, VectorParams.lgvParams)

  private def clear(data: Data): Unit = data match {
    case b: Bool => b.poke(false.B)
    case u: UInt => u.poke(0.U)
    case s: SInt => s.poke(0.S)
    case v: Vec[_] => v.foreach(clear)
    case b: Bundle => b.elements.values.foreach(clear)
  }

  behavior of "Independent operand collection and reduction streaming"

  it should "collect a safe source during RAW and hold both values until WAR and WAW clear" in {
    implicit val p: Parameters = parameters
    test(LazyModule(new EvuIndependentOperandShell).module)
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        clear(dut.io.command.bits)
        val command = dut.io.command.bits
        command.bits.poke(((OPFFunct6.fadd.litValue << 26) | (BigInt(1) << 25) |
          (BigInt(2) << 20) | (BigInt(1) << 15) | (BigInt(1) << 12) | (3 << 7) | 0x57).U)
        command.vconfig.vl.poke(8.U)
        command.vconfig.vtype.vsew.poke(2.U)
        command.renv1.poke(true.B)
        command.renv2.poke(true.B)
        command.wvd.poke(true.B)
        dut.io.olderWrites.poke((BigInt(1) << 4).U) // v1 E0, four EGs/register.
        dut.io.olderReads.poke((BigInt(1) << 12).U) // Destination WAR.
        dut.io.readReady.foreach(_.poke(true.B))
        for (i <- 0 until 4) dut.io.readData(i).poke((0x100 + i).U)
        dut.io.feedback.poke(true.B)
        dut.io.issueReady.poke(true.B)
        dut.io.writebackReady.poke(true.B)
        dut.io.command.valid.poke(true.B)
        dut.io.command.ready.expect(true.B)
        dut.clock.step()
        dut.io.command.valid.poke(false.B)
        dut.io.readValid(0).expect(false.B)
        dut.io.readValid(1).expect(true.B)
        dut.io.early.expect(1.U)
        dut.io.issue.expect(false.B)
        dut.clock.step()
        dut.io.readData(1).poke(0xdead.U)
        dut.io.readValid(1).expect(false.B)
        dut.io.data(1).expect(0x101.U)
        dut.io.olderWrites.poke((BigInt(1) << 12).U) // RAW clears, destination WAW stays.
        dut.io.readValid(0).expect(true.B)
        dut.io.early.expect(1.U)
        dut.io.issue.expect(false.B)
        dut.clock.step()
        dut.io.readData(0).poke(0xbeef.U)
        dut.io.readValid.foreach(_.expect(false.B))
        dut.io.data(0).expect(0x100.U)
        dut.io.data(1).expect(0x101.U)
        dut.io.olderWrites.poke(0.U)
        dut.io.issue.expect(false.B) // WAR still protects the destination.
        dut.clock.step(2)
        dut.io.olderReads.poke(0.U)
        dut.io.issue.expect(true.B)
        dut.io.data(0).expect(0x100.U)
        dut.io.data(1).expect(0x101.U)
        dut.clock.step()
        dut.io.issue.expect(false.B)
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.issue.expect(false.B)
        dut.io.readValid.foreach(_.expect(false.B))
      }
  }

  for (alias <- Seq(false, true); masked <- Seq(false, true)) {
    it should s"prefetch only legal streaming inputs and stop at merge/fold (alias=$alias masked=$masked)" in {
      implicit val p: Parameters = parameters
      test(LazyModule(new EvuIndependentOperandShell).module)
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          clear(dut.io.command.bits)
          val command = dut.io.command.bits
          command.bits.poke(((OPFFunct6.fredusum.litValue << 26) |
            (if (masked) BigInt(0) else BigInt(1) << 25) | (BigInt(2) << 20) |
            (BigInt(1) << 15) | (BigInt(1) << 12) | ((if (alias) 2 else 3) << 7) | 0x57).U)
          command.vconfig.vl.poke(24.U)
          command.vconfig.vtype.vsew.poke(2.U)
          command.reduction.poke(true.B)
          command.renv1.poke(true.B)
          command.renv2.poke(true.B)
          command.renvm.poke(masked.B)
          command.wvd.poke(true.B)
          dut.io.olderWrites.poke(0.U)
          dut.io.olderReads.poke(0.U)
          dut.io.readReady.foreach(_.poke(true.B))
          dut.io.readData.foreach(_.poke(0.U))
          dut.io.feedback.poke(true.B)
          dut.io.issueReady.poke(true.B)
          dut.io.writebackReady.poke(true.B)
          dut.io.command.valid.poke(true.B)
          dut.clock.step()
          dut.io.command.valid.poke(false.B)
          dut.io.index.expect(0.U)
          dut.io.issue.expect(true.B)
          dut.io.prefetch(0).valid.expect(false.B)
          dut.io.prefetch(2).valid.expect(false.B)
          dut.io.prefetch(1).valid.expect((!masked).B)
          dut.io.prefetch(1).bits.eg.expect(9.U)
          // A pending older writer to the next EG blocks only prefetch.
          dut.io.olderWrites.poke((BigInt(1) << 9).U)
          dut.io.issue.expect(true.B)
          dut.io.prefetch(1).valid.expect(false.B)
          dut.clock.step()
          dut.io.index.expect(8.U)
          dut.io.issue.expect(false.B)
          dut.io.readValid(1).expect(false.B)
          dut.io.olderWrites.poke(0.U)
          dut.io.issue.expect(true.B)
          dut.io.prefetch(1).bits.eg.expect(10.U)
          dut.io.prefetch(1).valid.expect((!masked).B)
          dut.io.issueReady.poke(false.B)
          dut.io.prefetch.foreach(_.valid.expect(false.B))
          dut.clock.step(2)
          dut.io.issueReady.poke(true.B)
          dut.clock.step()
          dut.io.index.expect(16.U)
          dut.io.issue.expect(true.B)
          dut.io.prefetch.foreach(_.valid.expect(false.B)) // Last input.
          dut.clock.step()
          dut.io.feedback.poke(false.B)
          dut.io.issue.expect(false.B)
          dut.io.prefetch.foreach(_.valid.expect(false.B))
          dut.clock.step(2)
          dut.io.feedback.poke(true.B)
          // Three merges plus three FP32 folds. No source-stream prefetch.
          for (_ <- 0 until 6) {
            dut.io.issue.expect(true.B)
            dut.io.prefetch.foreach(_.valid.expect(false.B))
            dut.clock.step()
          }
          dut.io.issue.expect(false.B)
        }
    }
  }
}
