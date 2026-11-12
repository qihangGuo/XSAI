package xiangshan.evucheck

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import top.ArgParser
import xiangshan.{CSROpType, FenceOpType, MSetOpType, XSCoreParamsKey, XSTileKey}
import xiangshan.backend.exu.{Dispatcher, ExeUnit}
import xiangshan.backend.fu.FuType

class SelectedFuDispatchHarness(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val selected = Input(UInt(2.W))
    val ready = Input(Vec(4, Bool()))
    val inputReady = Output(Bool())
    val inputFire = Output(Bool())
    val outputFire = Output(Vec(4, Bool()))
  })
  val dispatch = Module(new Dispatcher(UInt(2.W), 4,
    (selected: UInt) => (0 until 4).map(i => selected === i.U)))
  dispatch.io.in.valid := io.valid
  dispatch.io.in.bits := io.selected
  dispatch.io.out.zip(io.ready).foreach { case (out, ready) => out.ready := ready }
  io.inputReady := dispatch.io.in.ready
  io.inputFire := dispatch.io.in.fire
  io.outputFire := VecInit(dispatch.io.out.map(_.fire))
}

/** Use the actual shared CSR/Fence/Div/Mcfg unit, including its busy tracking. */
class MatrixControlDispatchHarness(implicit p: Parameters) extends Module {
  private val backend = p(XSCoreParamsKey).backendParams
  backend.allSchdParams.foreach(_.bindBackendParam(backend))
  backend.allIssueParams.foreach(_.bindBackendParam(backend))
  backend.allExuParams.zipWithIndex.foreach { case (exu, index) =>
    exu.bindBackendParam(backend)
    exu.updateExuIdx(index)
  }
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val kind = Input(UInt(2.W)) // 0: MSYNCRESET, 1: MSETCFG, 2: MINIT, 3: MGETCFG
    val rob = Input(UInt(log2Ceil(p(XSCoreParamsKey).RobSize).W))
    val drained = Input(Bool())
    val outputReady = Input(Bool())
    val inputReady = Output(Bool())
    val outputValid = Output(Bool())
    val outputRob = Output(UInt(log2Ceil(p(XSCoreParamsKey).RobSize).W))
    val outputData = Output(UInt(64.W))
    val outputFlush = Output(Bool())
    val wantDrain = Output(Bool())
  })
  private val exu = backend.allExuParams.find(_.name == "BJU3").get
  val unit = Module(LazyModule(new ExeUnit(exu)).module)
  private def tieOffInputs(data: Data): Unit = data match {
    case record: Record => record.elements.values.foreach(tieOffInputs)
    case vector: Vec[_] => vector.foreach(tieOffInputs)
    case leaf if DataMirror.directionOf(leaf) == ActualDirection.Input =>
      leaf := 0.U.asTypeOf(leaf)
    case _ => ()
  }
  tieOffInputs(unit.io)
  unit.io.in.valid := io.valid
  unit.io.in.bits.fuType := Mux(io.kind === 0.U, FuType.fence.U,
    Mux(io.kind === 2.U, FuType.csr.U, FuType.mcfg.U))
  unit.io.in.bits.fuOpType := MuxLookup(io.kind, MSetOpType.msetcfg)(Seq(
    0.U -> FenceOpType.msyncregreset,
    2.U -> CSROpType.minit,
    3.U -> MSetOpType.mgetcfg))
  unit.io.in.bits.robIdx.value := io.rob
  unit.io.in.bits.src(0) := 2.U
  unit.io.in.bits.rfWen.foreach(_ := io.kind === 3.U)
  unit.io.in.bits.flushPipe.foreach(_ := io.kind === 1.U)
  unit.io.in.bits.mcfgReadRaw.foreach(_ := 4.U)
  unit.io.out.ready := io.outputReady
  unit.io.csrio.get.evu.drained := io.drained
  unit.io.fenceio.get.sbuffer.sbIsEmpty := true.B
  io.inputReady := unit.io.in.ready
  io.outputValid := unit.io.out.valid
  io.outputRob := unit.io.out.bits.robIdx.value
  io.outputData := unit.io.out.bits.data(0)
  io.outputFlush := unit.io.out.bits.flushPipe.get
  io.wantDrain := unit.io.csrio.get.evu.wantDrain
}

class McfgDispatchSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Matrix control dispatch handshakes"

  private def parameters: Parameters = {
    val (base, _, _) = ArgParser.parse(Array("--config", "DefaultExternalVectorMatrixConfig",
      "--disable-perf", "--disable-alwaysdb"))
    base.alterPartial {
      case XSCoreParamsKey => base(XSTileKey).head.copy(RobSize = 32, EnableClockGate = false)
    }
  }

  it should "conserve handshakes for every selected FU and ready combination" in {
    implicit val p: Parameters = parameters
    test(new SelectedFuDispatchHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      for (valid <- Seq(false, true); selected <- 0 until 4; mask <- 0 until 16) {
        dut.io.valid.poke(valid.B)
        dut.io.selected.poke(selected.U)
        for (i <- 0 until 4) dut.io.ready(i).poke(((mask & (1 << i)) != 0).B)
        val ready = (mask & (1 << selected)) != 0
        dut.io.inputReady.expect(ready.B)
        dut.io.inputFire.expect((valid && ready).B)
        for (i <- 0 until 4)
          dut.io.outputFire(i).expect((valid && ready && i == selected).B)
        dut.clock.step()
      }
    }
  }

  it should "complete MSYNCRESET then MSETCFG without a CSR drain certificate" in {
    implicit val p: Parameters = parameters
    test(new MatrixControlDispatchHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.io.valid.poke(false.B)
      dut.io.kind.poke(0.U)
      dut.io.rob.poke(6.U)
      dut.io.drained.poke(false.B)
      dut.io.outputReady.poke(true.B)
      dut.clock.step(3)
      // SETVLI invalidates the previous certificate even after EVU finishes.
      dut.io.valid.poke(true.B)
      dut.io.inputReady.expect(true.B)
      dut.io.wantDrain.expect(false.B)
      dut.clock.step()
      // The following MSETCFG must wait while the accepted Fence returns.
      dut.io.kind.poke(1.U)
      dut.io.rob.poke(7.U)
      dut.io.inputReady.expect(false.B)
      dut.io.outputValid.expect(true.B)
      dut.io.outputRob.expect(6.U)
      dut.clock.step()
      dut.io.inputReady.expect(true.B)
      dut.io.outputValid.expect(true.B)
      dut.io.outputRob.expect(7.U)
      dut.io.outputData.expect(2.U)
      dut.io.outputFlush.expect(true.B)
      dut.clock.step()
      dut.io.valid.poke(false.B)
      for (_ <- 0 until 8) {
        dut.io.outputValid.expect(false.B)
        dut.clock.step()
      }
    }
  }

  it should "retain drain protection for a selected CSR and backpressure for MGETCFG" in {
    implicit val p: Parameters = parameters
    test(new MatrixControlDispatchHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.io.valid.poke(false.B)
      dut.io.kind.poke(2.U)
      dut.io.rob.poke(8.U)
      dut.io.drained.poke(false.B)
      dut.io.outputReady.poke(true.B)
      dut.clock.step(3)
      dut.io.valid.poke(true.B)
      for (_ <- 0 until 4) {
        dut.io.inputReady.expect(false.B)
        dut.io.outputValid.expect(false.B)
        dut.io.wantDrain.expect(true.B)
        dut.clock.step()
      }
      dut.io.drained.poke(true.B)
      dut.io.inputReady.expect(true.B)
      dut.clock.step()
      dut.io.valid.poke(false.B)
      dut.io.outputValid.expect(true.B)
      dut.io.outputRob.expect(8.U)
      dut.clock.step()
      dut.io.kind.poke(3.U)
      dut.io.rob.poke(9.U)
      dut.io.drained.poke(false.B)
      dut.io.outputReady.poke(false.B)
      dut.io.valid.poke(true.B)
      for (_ <- 0 until 4) {
        dut.io.inputReady.expect(false.B)
        dut.io.outputValid.expect(true.B)
        dut.io.outputRob.expect(9.U)
        dut.io.outputData.expect(4.U)
        dut.io.outputFlush.expect(false.B)
        dut.clock.step()
      }
      dut.io.outputReady.poke(true.B)
      dut.io.inputReady.expect(true.B)
      dut.clock.step()
      dut.io.valid.poke(false.B)
      dut.io.outputValid.expect(false.B)
    }
  }
}
