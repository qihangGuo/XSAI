package xiangshan.evucheck

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import top.ArgParser
import xiangshan.{ExceptionNO, MSetOpType, XSCoreParamsKey, XSTileKey}
import xiangshan.backend.BackendParams
import xiangshan.backend.fu.FuConfig
import xiangshan.backend.fu.wrapper.Mcfg
import xiangshan.backend.rob.ExceptionGen

/** Exercise the production writeback path without relying on an enqueue hint. */
class McfgSerializationHarness(implicit p: Parameters) extends Module {
  private val backend: BackendParams = p(XSCoreParamsKey).backendParams
  backend.allSchdParams.foreach(_.bindBackendParam(backend))
  backend.allIssueParams.foreach(_.bindBackendParam(backend))
  backend.allExuParams.foreach(_.bindBackendParam(backend))
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val ready = Input(Bool())
    val read = Input(Bool())
    val decodedFlush = Input(Bool())
    val value = Input(UInt(64.W))
    val oldValue = Input(UInt(64.W))
    val rob = Input(UInt(log2Ceil(p(XSCoreParamsKey).RobSize).W))
    val flush = Input(Bool())
    val accepted = Output(Bool())
    val result = Output(UInt(64.W))
    val wbFlush = Output(Bool())
    val wbIllegal = Output(Bool())
    val recordValid = Output(Bool())
    val recordRob = Output(UInt(log2Ceil(p(XSCoreParamsKey).RobSize).W))
    val recordFlush = Output(Bool())
    val recordIllegal = Output(Bool())
  })
  val unit = Module(new Mcfg(FuConfig.McfgCfg))
  unit.io.in.bits := 0.U.asTypeOf(unit.io.in.bits)
  unit.io.flush := 0.U.asTypeOf(unit.io.flush)
  unit.io.in.valid := io.valid
  unit.io.out.ready := io.ready
  unit.io.in.bits.ctrl.fuOpType := Mux(io.read, MSetOpType.mgetcfg, MSetOpType.msetcfg)
  unit.io.in.bits.ctrl.flushPipe.foreach(_ := io.decodedFlush)
  unit.io.in.bits.ctrl.robIdx.value := io.rob
  unit.io.in.bits.ctrl.mcfgReadRaw.get := io.oldValue
  unit.io.in.bits.data.src(0) := io.value
  io.accepted := unit.io.out.fire
  io.result := unit.io.out.bits.res.data
  io.wbFlush := unit.io.out.bits.ctrl.flushPipe.getOrElse(false.B)
  io.wbIllegal := unit.io.out.bits.ctrl.exceptionVec.get(ExceptionNO.illegalInstr)

  val exceptions = Module(new ExceptionGen(backend))
  exceptions.io.enq := 0.U.asTypeOf(exceptions.io.enq)
  exceptions.io.wb := 0.U.asTypeOf(exceptions.io.wb)
  exceptions.io.redirect := 0.U.asTypeOf(exceptions.io.redirect)
  exceptions.io.flush := io.flush
  val index = backend.allExuParams.filter(_.exceptionOut.nonEmpty)
    .indexWhere(_.fuConfigs.contains(FuConfig.McfgCfg))
  require(index >= 0)
  val wb = exceptions.io.wb(index)
  wb.valid := unit.io.out.fire
  wb.bits.robIdx := unit.io.out.bits.ctrl.robIdx
  wb.bits.exceptionVec := unit.io.out.bits.ctrl.exceptionVec.get
  wb.bits.hasException := io.wbIllegal
  wb.bits.flushPipe := io.wbFlush
  io.recordValid := exceptions.io.state.valid
  io.recordRob := exceptions.io.state.bits.robIdx.value
  io.recordFlush := exceptions.io.state.bits.flushPipe
  io.recordIllegal := exceptions.io.state.bits.exceptionVec(ExceptionNO.illegalInstr)
}

class McfgSerializationSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Matrix configuration writeback serialization"

  private def parameters: Parameters = {
    val (base, _, _) = ArgParser.parse(Array("--config", "DefaultExternalVectorMatrixConfig",
      "--disable-perf", "--disable-alwaysdb"))
    base.alterPartial { case XSCoreParamsKey => base(XSTileKey).head.copy(RobSize = 32) }
  }

  it should "deliver a flush record at writeback even without an enqueue-time record" in {
    implicit val p: Parameters = parameters
    test(new McfgSerializationHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.valid.poke(true.B)
      dut.io.ready.poke(false.B)
      dut.io.read.poke(false.B)
      dut.io.decodedFlush.poke(true.B)
      dut.io.value.poke(2.U) // The failing PC writes INT8 into mcfg0.
      dut.io.oldValue.poke(0.U)
      dut.io.rob.poke(7.U)
      dut.io.flush.poke(false.B)
      for (_ <- 0 until 4) {
        dut.io.accepted.expect(false.B)
        dut.io.result.expect(2.U)
        dut.io.wbFlush.expect(true.B)
        dut.io.wbIllegal.expect(false.B)
        dut.io.recordValid.expect(false.B)
        dut.clock.step()
      }
      dut.io.ready.poke(true.B)
      dut.clock.step()
      dut.io.valid.poke(false.B)
      dut.clock.step(4)
      dut.io.recordValid.expect(true.B)
      dut.io.recordRob.expect(7.U)
      dut.io.recordFlush.expect(true.B)
      dut.io.recordIllegal.expect(false.B)
    }
  }

  it should "leave reads unserialized and report an unsupported write as an exception" in {
    implicit val p: Parameters = parameters
    test(new McfgSerializationHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.valid.poke(true.B)
      dut.io.ready.poke(true.B)
      dut.io.read.poke(true.B)
      dut.io.decodedFlush.poke(false.B)
      dut.io.value.poke(15.U)
      dut.io.oldValue.poke(4.U)
      dut.io.rob.poke(9.U)
      dut.io.flush.poke(false.B)
      dut.io.result.expect(4.U)
      dut.io.wbFlush.expect(false.B)
      dut.io.wbIllegal.expect(false.B)
      dut.clock.step()
      dut.io.valid.poke(false.B)
      dut.clock.step(4)
      dut.io.recordValid.expect(false.B)
      dut.io.valid.poke(true.B)
      dut.io.read.poke(false.B)
      dut.io.decodedFlush.poke(true.B)
      dut.io.wbFlush.expect(false.B)
      dut.io.wbIllegal.expect(true.B)
      dut.clock.step()
      dut.io.valid.poke(false.B)
      dut.clock.step(4)
      dut.io.recordValid.expect(true.B)
      dut.io.recordRob.expect(9.U)
      dut.io.recordFlush.expect(false.B)
      dut.io.recordIllegal.expect(true.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.recordValid.expect(false.B)
    }
  }
}
