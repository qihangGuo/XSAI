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
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.{ExecuteSequencer, SpecialSequencer}
import xiangshan.evu.common._
import xiangshan.evu.exu._

class EvuAlignedSlideHarness(implicit parameters: Parameters) extends CoreModule with HasVectorParams {
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new BackendIssueInst))
    val olderReads = Input(UInt(egsTotal.W))
    val olderWrites = Input(UInt(egsTotal.W))
    val readReady = Input(Bool())
    val readData = Input(UInt(dLen.W))
    val issueReady = Input(Bool())
    val writeReady = Input(Bool())
    val accepted = Output(Bool())
    val read = Output(Valid(UInt(log2Ceil(egsTotal).W)))
    val specialAccepts = Output(Bool())
    val buffered = Output(Bool())
    val output = Output(Valid(new VectorWrite(dLen)))
  })
  val insns = VXSequencerParams("aligned_slide", Seq(PermuteUnitFactory)).insns
  val seq = Module(new ExecuteSequencer(insns, 1, 1))
  val special = Module(new SpecialSequencer(insns))
  special.io.dis.valid := false.B
  special.io.dis.bits := io.command.bits
  special.io.dis_stall := false.B
  special.io.older_reads := 0.U
  special.io.older_writes := 0.U
  special.io.vat_head := 0.U
  special.io.iss.ready := false.B
  special.io.rvs2.ready := false.B
  special.io.rvm.ready := false.B
  special.io.acc_data.ready := false.B
  special.io.acc_slot := 0.U
  special.io.acc_other_slot := 0.U
  special.io.acc_merge := false.B
  special.io.acc_init_resp := 0.U
  special.io.rvm_resp := 0.U
  special.io.acc_fu_resp := 0.U.asTypeOf(special.io.acc_fu_resp)
  special.io.acc_fu_resp_slot := 0.U
  special.io.acc_done := false.B
  io.specialAccepts := special.accepts(io.command.bits)
  seq.io.dis <> io.command
  seq.io.dis_stall := false.B
  seq.io.older_reads := io.olderReads
  seq.io.older_writes := io.olderWrites
  seq.io.vat_head := 0.U
  seq.io.acc_valid := true.B
  seq.io.acc_return := false.B
  seq.io.lookahead := 0.U.asTypeOf(seq.io.lookahead)
  seq.io.pipe_write_req.available := io.writeReady
  seq.io.vgu.slide_req.ready := false.B
  seq.io.vgu.slide_data := 0.U
  seq.io.vgu.gather_eidx.valid := false.B
  seq.io.vgu.gather_eidx.bits := 0.U
  for (read <- Seq(seq.io.rvs1, seq.io.rvs2, seq.io.rvd, seq.io.rvm)) {
    read.req.ready := io.readReady
    read.resp := io.readData
  }
  io.read.valid := seq.io.rvs2.req.valid
  io.read.bits := seq.io.rvs2.req.bits.eg
  io.buffered := seq.io.vgu.slide_req.valid
  val unit = Module(new ExecutionUnit(Seq(PermuteUnitFactory), "aligned_slide_test"))
  unit.io.iss.valid := seq.io.iss.valid && io.issueReady
  seq.io.iss.ready := unit.io.iss.ready && io.issueReady
  unit.io.iss.bits := 0.U.asTypeOf(unit.io.iss.bits)
  unit.io.iss.bits.viewAsSupertype(new ExecuteMicroOp(1)) := seq.io.iss.bits
  unit.io.iss.bits.rvs2_data := Mux(seq.io.iss.bits.use_zero_rvs2, 0.U,
    Mux(seq.io.iss.bits.use_slide_rvs2, seq.io.iss.bits.slide_data, seq.io.rvs2_data))
  unit.io.iss.bits.wmask := seq.io.iss.bits.eidx_mask
  unit.io.iter_write.ready := true.B
  unit.io.scalar_write.ready := true.B
  unit.io.shared_fp_req.ready := true.B
  unit.io.shared_fp_resp := 0.U.asTypeOf(unit.io.shared_fp_resp)
  io.output := unit.io.pipe_write
  io.accepted := seq.io.iss.fire
}

class EvuAlignedSlideShell(implicit parameters: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("aligned-slide-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new EvuAlignedSlideShellImp(this)
}

class EvuAlignedSlideShellImp(outer: EvuAlignedSlideShell)(implicit parameters: Parameters)
    extends LazyModuleImp(outer) {
  val harness = Module(new EvuAlignedSlideHarness()(parameters.alterPartial {
    case TileVisibilityNodeKey => outer.client
  }))
  val io = IO(chiselTypeOf(harness.io))
  io <> harness.io
}

class EvuAlignedSlideSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val base = Parameters.empty.alterPartial {
    case MonitorsEnabled => false
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }
  private def clear(data: Data): Unit = data match {
    case value: Bool => value.poke(false.B)
    case value: UInt => value.poke(0.U)
    case value: SInt => value.poke(0.S)
    case values: Vec[_] => values.foreach(clear)
    case bundle: Bundle => bundle.elements.values.foreach(clear)
  }

  behavior of "Aligned element-group slide bypass"

  for (datapath <- Seq(128, 256, 512)) {
    it should s"move whole groups with existing RAW/WAR/WAW checks and backpressure at DLEN=$datapath" in {
      implicit val parameters: Parameters = EvuVectorParameters(base, 4 * datapath, datapath, datapath,
        VectorParams.lgvParams.copy(alignedSlide = true))
      test(LazyModule(new EvuAlignedSlideShell).module)
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          dut.clock.setTimeout(100000)
          val groups = 4
          val groupBytes = datapath / 8
          val vectorBytes = groups * groupBytes
          val fullMask = (BigInt(1) << datapath) - 1
          val pending = scala.collection.mutable.Queue.empty[(Int, BigInt)]
          val writes = scala.collection.mutable.ArrayBuffer.empty[Int]
          val reads = scala.collection.mutable.ArrayBuffer.empty[Int]
          dut.io.command.valid.poke(false.B)
          dut.io.olderReads.poke(0.U)
          dut.io.olderWrites.poke(0.U)
          dut.io.readReady.poke(true.B)
          dut.io.readData.poke(0.U)
          dut.io.issueReady.poke(true.B)
          dut.io.writeReady.poke(true.B)
          def data(eg: Int): BigInt = BigInt(eg + 1) * ((BigInt(1) << datapath) - 1) / 255
          def tick(): Unit = {
            if (dut.io.output.valid.peek().litToBoolean) {
              pending.nonEmpty shouldBe true
              val (eg, expected) = pending.dequeue()
              dut.io.output.bits.eg.expect(eg.U)
              dut.io.output.bits.mask.expect(fullMask.U)
              dut.io.output.bits.data.expect(expected.U)
              writes += eg
            }
            dut.io.buffered.expect(false.B)
            dut.clock.step()
          }
          for (sew <- 0 to 2; down <- Seq(false, true); offsetGroups <- Seq(0, 1, groups - 1)) {
            clear(dut.io.command.bits)
            val command = dut.io.command.bits
            val offset = offsetGroups * (groupBytes >> sew)
            val function = if (down) OPIFunct6.slidedown else OPIFunct6.slideup
            command.bits.poke(((function.litValue << 26) | (BigInt(1) << 25) |
              (4 << 20) | (1 << 15) | (4 << 12) | (8 << 7) | 0x57).U)
            command.vconfig.vl.poke((vectorBytes >> sew).U)
            command.vconfig.vtype.vsew.poke(sew.U)
            command.rs1_data.poke(offset.U)
            command.wvd.poke(true.B)
            dut.io.specialAccepts.expect(false.B)
            dut.io.command.valid.poke(true.B)
            dut.io.command.ready.expect(true.B)
            tick()
            dut.io.command.valid.poke(false.B)
            reads.clear()
            writes.clear()
            val destinations = if (down) 0 until groups else offsetGroups until groups
            for (destination <- destinations) {
              val sourceGroup = if (down) destination + offsetGroups else destination - offsetGroups
              val inRange = sourceGroup < groups
              val sourceEg = 4 * groups + sourceGroup
              val destEg = 8 * groups + destination
              val expected = if (inRange) data(sourceEg) else BigInt(0)
              dut.io.readData.poke(expected.U)
              dut.io.issueReady.poke(false.B)
              dut.io.readReady.poke(false.B)
              dut.io.olderWrites.poke((if (inRange) BigInt(1) << sourceEg else BigInt(0)).U)
              dut.io.read.valid.expect(false.B)
              tick()
              dut.io.olderWrites.poke((BigInt(1) << destEg).U)
              dut.io.read.valid.expect(false.B)
              tick()
              dut.io.olderWrites.poke(0.U)
              dut.io.olderReads.poke((BigInt(1) << destEg).U)
              dut.io.read.valid.expect(false.B)
              tick()
              dut.io.olderReads.poke(0.U)
              dut.io.read.valid.expect(inRange.B)
              if (inRange) {
                dut.io.read.bits.expect(sourceEg.U)
                dut.io.readReady.poke(true.B)
                reads += sourceEg
              }
              tick()
              dut.io.readReady.poke(false.B)
              dut.io.readData.poke(0.U)
              dut.io.read.valid.expect(false.B)
              dut.io.issueReady.poke(true.B)
              dut.io.writeReady.poke(false.B)
              dut.io.accepted.expect(false.B)
              tick()
              dut.io.writeReady.poke(true.B)
              dut.io.accepted.expect(true.B)
              pending.enqueue((destEg, expected))
              tick()
            }
            tick()
            pending shouldBe empty
            writes.toSeq shouldBe destinations.map(_ + 8 * groups)
            reads.size shouldBe groups - offsetGroups
          }
          val groupElements = groupBytes / 4
          val vectorElements = vectorBytes / 4
          for ((masked, start, offset, length) <- Seq((true, 0, groupElements, vectorElements),
              (false, 0, 1, vectorElements), (false, 1, groupElements, vectorElements),
              (false, 0, groupElements, vectorElements - 1), (false, 0, vectorElements, vectorElements))) {
            clear(dut.io.command.bits)
            val command = dut.io.command.bits
            command.bits.poke(((OPIFunct6.slidedown.litValue << 26) |
              (if (masked) BigInt(0) else BigInt(1) << 25) | (4 << 20) |
              (1 << 15) | (4 << 12) | (8 << 7) | 0x57).U)
            command.vconfig.vl.poke(length.U)
            command.vconfig.vtype.vsew.poke(2.U)
            command.vstart.poke(start.U)
            command.rs1_data.poke(offset.U)
            dut.io.specialAccepts.expect(true.B)
          }
        }
    }
  }
}
