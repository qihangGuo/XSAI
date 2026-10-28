package evu

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.{CoreModule, TileVisibilityNodeKey}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.{ExecuteSequencer, RegisterFile}
import xiangshan.evu.common._
import xiangshan.evu.exu.{PermuteUnit, PermuteUnitFactory}
import xiangshan.evu.insns._

class EvuGatherImmHarness(ep: EvuParams)(implicit p: Parameters) extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new EvuCommand(ep)))
    val lmul = Input(UInt(3.W))
    val legalGroup = Output(Bool())
    val initialize = Input(Valid(new VectorWrite(dLen)))
    val issueReady = Input(Bool())
    val issue = Output(Valid(UInt(log2Ceil(maxVLMax).W)))
    val reads = Output(Vec(4, Bool()))
    val busy = Output(Bool())
    val vrf = Output(Vec(egsTotal, UInt(dLen.W)))
  })
  val insns = VXSequencerParams("gather", Seq(PermuteUnitFactory)).insns
  val sequencer = Module(new ExecuteSequencer(insns, 2, 1))
  val permute = Module(new PermuteUnit)
  val vrf = Module(new RegisterFile(Seq(3), Seq(1), 2, 1, 2, "vrf_gather_imm"))
  val cmd = io.command.bits
  val inst = WireInit(0.U.asTypeOf(new BackendIssueInst))
  inst.bits := EvuSaturnGather.encodeImmediate(cmd)
  inst.vconfig.vl := cmd.vl
  inst.vconfig.vtype.vsew := cmd.sew
  inst.vconfig.vtype.vlmul_sign := io.lmul(2)
  inst.vconfig.vtype.vlmul_mag := Mux(io.lmul(2), (8.U - io.lmul)(1, 0), io.lmul(1, 0))
  inst.emul := Mux(io.lmul(2), 0.U, io.lmul(1, 0))
  val ctrl = Wire(new VectorDecodedControl(insns, Seq(ReadsVS1, ReadsVS2, VMBitReadsVM))).decode(inst)
  inst.renv1 := ctrl.bool(ReadsVS1)
  inst.renv2 := ctrl.bool(ReadsVS2)
  inst.renvm := !inst.vm && ctrl.bool(VMBitReadsVM)
  inst.wvd := true.B
  val custom = Cat(EvuIsa.gatherImm, cmd.vs2, cmd.vs1, cmd.sew, cmd.vd, EvuIsa.custom0)
  io.legalGroup := EvuIsa.supportsRegisterGroups(custom, io.lmul)
  sequencer.io.dis.valid := io.command.valid && cmd.vl =/= 0.U && io.legalGroup
  sequencer.io.dis.bits := inst
  sequencer.io.dis_stall := false.B
  io.command.ready := sequencer.io.dis.ready && io.legalGroup
  sequencer.io.older_writes := 0.U
  sequencer.io.older_reads := 0.U
  sequencer.io.vat_head := 0.U
  sequencer.io.acc_valid := false.B
  sequencer.io.vgu.slide_req.ready := true.B
  sequencer.io.vgu.slide_data := 0.U
  sequencer.io.vgu.gather_eidx.valid := false.B
  sequencer.io.vgu.gather_eidx.bits := 0.U
  val reads = Seq(sequencer.io.rvs1, sequencer.io.rvs2, sequencer.io.rvd, sequencer.io.rvm)
  val ports = vrf.io.read(0).toSeq :+ vrf.io.mask_read(0)(0)
  for ((read, port) <- reads.zip(ports)) {
    port.req <> read.req
    read.resp := port.resp
  }
  io.reads := VecInit(reads.map(_.req.fire))
  sequencer.io.pipe_write_req <> vrf.io.pipe_write_reqs(1)
  vrf.io.pipe_write_reqs(0).request := false.B
  vrf.io.pipe_write_reqs(0).fire := false.B
  vrf.io.pipe_write_reqs(0).bank_sel := 0.U
  vrf.io.pipe_write_reqs(0).pipe_depth := 0.U
  vrf.io.pipe_write_reqs(0).oldest := false.B
  vrf.io.pipe_writes(0) := io.initialize
  vrf.io.pipe_writes(1) := permute.io.write
  vrf.io.ll_writes(0).valid := false.B
  vrf.io.ll_writes(0).bits := 0.U.asTypeOf(vrf.io.ll_writes(0).bits)
  io.vrf := vrf.io.debug.get

  val micro = sequencer.io.iss.bits
  val data = WireInit(0.U.asTypeOf(new ExecuteMicroOpWithData(1)))
  data.viewAsSupertype(new ExecuteMicroOp(1)) := micro
  data.rvs1_data := dLenSplat(micro.scalar, micro.rvs1_eew)
  data.rvs2_data := Mux(micro.use_zero_rvs2, 0.U, sequencer.io.rvs2_data)
  val maskOffset = (micro.eidx & ~((dLenB - 1).U >> micro.vd_eew))(log2Ceil(dLen) - 1, 0)
  val maskBits = (sequencer.io.rvm_data >> maskOffset)(dLenB - 1, 0)
  val maskBytes = VecInit.tabulate(3) { sew =>
    FillInterleaved(1 << sew, maskBits)(dLenB - 1, 0)
  }(micro.vd_eew)
  data.wmask := micro.eidx_mask & Mux(micro.use_wmask, maskBytes, ~0.U(dLenB.W))
  sequencer.io.iss.ready := io.issueReady
  permute.io.iss.valid := sequencer.io.iss.fire
  permute.io.iss.op := data
  permute.io.pipe(0).valid := RegNext(sequencer.io.iss.fire, false.B)
  permute.io.pipe(0).bits := RegEnable(data, sequencer.io.iss.fire)
  permute.io.scalar_write.ready := true.B
  io.issue.valid := sequencer.io.iss.fire
  io.issue.bits := micro.eidx
  io.busy := sequencer.io.busy || permute.io.pipe(0).valid
}

class EvuGatherImmShell(val ep: EvuParams)(implicit p: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("gather-imm-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuGatherImmShellImp(this)
}

class EvuGatherImmShellImp(outer: EvuGatherImmShell)(implicit p: Parameters) extends LazyModuleImp(outer) {
  val harness = Module(new EvuGatherImmHarness(outer.ep)(p.alterPartial { case TileVisibilityNodeKey => outer.client }))
  val io = IO(chiselTypeOf(harness.io))
  io <> harness.io
  val bus = outer.client.out.head._1
  bus.a.valid := false.B
  bus.a.bits := 0.U.asTypeOf(bus.a.bits)
  bus.d.ready := true.B
  val manager = outer.sink.in.head._1
  manager.a.ready := true.B
  manager.d.valid := false.B
  manager.d.bits := 0.U.asTypeOf(manager.d.bits)
}

class EvuGatherImmSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU immediate gather through the synchronous 1RW VRF"

  it should "broadcast with one source read and preserve unsigned indices, tails, masks and aliases" in {
    val ep = EvuParams(enable = true, enableDifftest = true, vlenBits = 2048)
    implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, ep.vlenBits, ep.dlenBits, ep.mlenBits, VectorParams.lgvParams.copy(debugVrf = true))
    test(LazyModule(new EvuGatherImmShell(ep)).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.clock.setTimeout(10000)
      dut.io.command.valid.poke(false.B)
      dut.io.initialize.valid.poke(false.B)
      dut.io.issueReady.poke(true.B)
      dut.io.lmul.poke(0.U)
      val chunkMask = (BigInt(1) << ep.dlenBits) - 1
      def pack(values: Seq[BigInt], width: Int): BigInt =
        values.zipWithIndex.foldLeft(BigInt(0)) { case (bits, (value, i)) => bits | (value << (width * i)) }
      def put(reg: Int, bits: BigInt): Unit = {
        for (chunk <- 0 until ep.vlenBits / ep.dlenBits) {
          dut.io.initialize.valid.poke(true.B)
          dut.io.initialize.bits.eg.poke((reg * (ep.vlenBits / ep.dlenBits) + chunk).U)
          dut.io.initialize.bits.data.poke(((bits >> (chunk * ep.dlenBits)) & chunkMask).U)
          dut.io.initialize.bits.mask.poke(chunkMask.U)
          dut.clock.step()
        }
        dut.io.initialize.valid.poke(false.B)
      }
      def expectReg(reg: Int, bits: BigInt): Unit = {
        for (chunk <- 0 until ep.vlenBits / ep.dlenBits)
          dut.io.vrf(reg * (ep.vlenBits / ep.dlenBits) + chunk)
            .expect(((bits >> (chunk * ep.dlenBits)) & chunkMask).U)
      }
      val mask = (0 until ep.vlenBytes).filter(_ % 3 != 1).foldLeft(BigInt(0))((bits, i) => bits.setBit(i))
      put(0, mask)
      val cases = Seq((2, 0, 64, false), (2, 31, 13, true),
        (1, 16, 13, false), (1, 7, 64, true), (0, 31, 1, false), (0, 0, 64, true))
      for ((sew, index, vl, alias) <- cases) {
        val width = 8 << sew
        val count = ep.vlenBits / width
        val values = (0 until count).map(i => BigInt((i * 17 + 19) & 255))
        val sentinel = (BigInt(1) << width) - 7
        val dest = if (alias) 1 else 4
        val before = if (alias) values else Seq.fill(count)(sentinel)
        put(1, pack(values, width))
        if (!alias) put(dest, pack(before, width))
        val masked = vl == 13
        val c = dut.io.command.bits
        c.opcode.poke(EvuOpcode.GatherImm)
        c.sew.poke(sew.U)
        c.vd.poke(dest.U)
        c.vs1.poke(index.U)
        c.vs2.poke(1.U)
        c.vl.poke(vl.U)
        c.aux.poke((if (masked) 2 else 0).U)
        dut.io.command.valid.poke(true.B)
        dut.io.command.ready.expect(true.B)
        dut.clock.step()
        dut.io.command.valid.poke(false.B)
        var cycles = 0
        var sourceReads = 0
        val issues = scala.collection.mutable.ArrayBuffer.empty[Int]
        val uninterrupted = sew == 2 && index == 0 && vl == 64 && !alias
        while (dut.io.busy.peek().litToBoolean && cycles < 200) {
          // Stall after the source response to exercise cached data and splats.
          dut.io.issueReady.poke((uninterrupted || cycles < 2 || cycles > 5).B)
          if (dut.io.reads(1).peek().litToBoolean) sourceReads += 1
          if (dut.io.issue.valid.peek().litToBoolean) issues += cycles
          dut.clock.step()
          cycles += 1
        }
        dut.io.issueReady.poke(true.B)
        dut.io.busy.expect(false.B)
        sourceReads shouldBe 1
        issues.size shouldBe ((vl + (ep.dlenBytes >> sew) - 1) / (ep.dlenBytes >> sew))
        if (uninterrupted) {
          issues.toSeq shouldBe (1 to 8)
          println(s"EVU FP32 VL64 broadcast: source_reads=$sourceReads micro_ops=${issues.size} consecutive_issue_cycles=8")
        }
        val expected = before.zipWithIndex.map { case (value, lane) =>
          if (lane < vl && (!masked || mask.testBit(lane))) values(index) else value
        }
        expectReg(dest, pack(expected, width))
        if (!alias) expectReg(1, pack(values, width))
      }
      // rs1=31 is an immediate, so it need not be aligned to an LMUL=8 group.
      dut.io.lmul.poke(3.U)
      dut.io.command.bits.vd.poke(8.U)
      dut.io.command.bits.vs2.poke(16.U)
      dut.io.command.bits.vs1.poke(31.U)
      dut.io.legalGroup.expect(true.B)
      dut.io.command.bits.vd.poke(9.U)
      dut.io.legalGroup.expect(false.B)
    }
  }
}
