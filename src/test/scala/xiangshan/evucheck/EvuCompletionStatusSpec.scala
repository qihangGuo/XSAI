package xiangshan.evucheck

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util._
import chiseltest._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import top.ArgParser
import xiangshan.{ExternalVectorKey, XSCoreParamsKey, XSTileKey}
import xiangshan.backend.BackendParams
import xiangshan.backend.fu.NewCSR.MstatusModule
import xiangshan.backend.rob.AmuCtrlBuffer
import xiangshan.evu.EvuCompletion

class EvuCompletionStatusHarness(implicit p: Parameters) extends Module {
  private implicit val backend: BackendParams = p(XSCoreParamsKey).backendParams
  backend.allSchdParams.foreach(_.bindBackendParam(backend))
  backend.allIssueParams.foreach(_.bindBackendParam(backend))
  backend.allExuParams.foreach(_.bindBackendParam(backend))
  private val ep = p(ExternalVectorKey)
  val io = IO(new Bundle {
    val enqueue = Input(Bool())
    val writeback = Input(Bool())
    val commit = Input(Bool())
    val completion = Flipped(Decoupled(new EvuCompletion(ep)))
    val redirect = Input(Bool())
    val initialize = Input(Bool())
    val dirtyVs = Output(Bool())
    val completionEvent = Output(Bool())
    val mstatus = Output(UInt(64.W))
    val sstatus = Output(UInt(64.W))
  })
  val amu = Module(new AmuCtrlBuffer)
  amu.io.enq := 0.U.asTypeOf(amu.io.enq)
  amu.io.enq(0).valid := io.enqueue
  amu.io.enq(0).reqValid := io.enqueue
  amu.io.enq(0).needAMU := true.B
  amu.io.wb := 0.U.asTypeOf(amu.io.wb)
  val wb = amu.io.wb.find(_.bits.amuCtrl.nonEmpty).get
  wb.valid := io.writeback
  wb.bits.amuCtrl.get.evu := true.B
  amu.io.deqCommit := 0.U.asTypeOf(amu.io.deqCommit)
  amu.io.deqCommit(0).valid := io.commit
  amu.io.redirect := 0.U.asTypeOf(amu.io.redirect)
  amu.io.redirect.valid := io.redirect
  amu.io.redirect.all := io.redirect
  amu.io.toAMU.foreach(_.ready := true.B)
  amu.io.hartId.foreach(_ := 0.U)
  amu.io.evuCompletion.get <> io.completion
  io.dirtyVs := amu.io.evuDirtyVs
  io.completionEvent := ShiftRegister(io.completion.fire, 3, false.B, true.B)

  val status = Module(new MstatusModule)
  for ((name, port) <- DataMirror.modulePorts(status)
       if name != "clock" && name != "reset" && DataMirror.directionOf(port) == ActualDirection.Input) {
    val inactive = WireDefault(0.U(port.getWidth.W))
    port := inactive.asTypeOf(port)
  }
  status.w.wen := io.initialize
  status.w.wdata := 0x2200.U // VS=Initial, FS=Initial, as in the guest startup.
  // Rob forwards the already-registered AMU pulse; NewCSR registers it once
  // before MstatusModule consumes it. Match that production CSR input stage.
  status.robCommit.vsDirty := RegNext(amu.io.evuDirtyVs, false.B)
  io.mstatus := status.rdata
  io.sstatus := status.sstatusRdata
}

class EvuCompletionStatusSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU ordered context status"

  it should "publish VS with the completion event and suppress faults, redirects and unchanged contexts" in {
    val (base, _, _) = ArgParser.parse(Array("--config", "DefaultExternalVectorMatrixConfig",
      "--disable-perf", "--disable-alwaysdb"))
    implicit val p: Parameters = base.alterPartial {
      case XSCoreParamsKey => base(XSTileKey).head.copy(RobSize = 32)
    }
    test(new EvuCompletionStatusHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      for ((opcode, dirty, fault, redirect) <- Seq(
        (0x10, true, false, false), // LOAD
        (1, true, false, false), // ADD, including conservative empty ALU completion
        (0x45, true, false, false), // SETVLI
        (0x46, true, false, false), // SETVL
        (0x11, false, false, false), // STORE
        (0x19, false, false, false), // WAIT
        (0x1a, false, false, false), // FENCE
        (0x50, false, false, false), // packed FP quantization to CUTE
        (0x10, false, false, false), // zero-length LOAD
        (0x10, true, true, false), // failed LOAD
        (0x10, true, false, true) // redirected completion
      )) {
        dut.io.enqueue.poke(false.B)
        dut.io.writeback.poke(false.B)
        dut.io.commit.poke(false.B)
        dut.io.redirect.poke(false.B)
        dut.io.initialize.poke(false.B)
        dut.io.completion.valid.poke(false.B)
        val c = dut.io.completion.bits
        c.opcode.poke(opcode.U)
        c.vd.poke(1.U)
        c.exception.poke(fault.B)
        c.fflags.poke(0.U)
        c.vxsat.poke(false.B)
        c.dirtyVs.poke(dirty.B)
        c.cause.poke((if (fault) 6 else 0).U)
        c.jobId.poke(0.U)
        c.epoch.poke(0.U)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.io.initialize.poke(true.B)
        dut.clock.step()
        dut.io.initialize.poke(false.B)
        def expectVs(value: Int): Unit = {
          ((dut.io.mstatus.peek().litValue >> 9) & 3) shouldBe BigInt(value)
          ((dut.io.sstatus.peek().litValue >> 9) & 3) shouldBe BigInt(value)
          ((dut.io.mstatus.peek().litValue >> 13) & 3) shouldBe BigInt(1)
        }
        expectVs(1)
        // A completion cannot update the CSR until its AMU entry was accepted.
        dut.io.completion.valid.poke(true.B)
        dut.io.completion.ready.expect(false.B)
        dut.clock.step(3)
        expectVs(1)
        dut.io.enqueue.poke(true.B)
        dut.clock.step()
        dut.io.enqueue.poke(false.B)
        dut.io.writeback.poke(true.B)
        dut.clock.step()
        dut.io.writeback.poke(false.B)
        dut.io.commit.poke(true.B)
        dut.clock.step()
        dut.io.commit.poke(false.B)
        dut.clock.step() // AMU command acceptance
        dut.io.redirect.poke(redirect.B)
        dut.io.completion.ready.expect((!redirect).B)
        dut.io.dirtyVs.expect(false.B)
        dut.clock.step() // completion acceptance, unless redirected
        dut.io.completion.valid.poke(false.B)
        dut.io.redirect.poke(false.B)
        val changed = dirty && !fault && !redirect
        dut.io.dirtyVs.expect(changed.B)
        expectVs(1)
        dut.clock.step()
        dut.io.dirtyVs.expect(false.B)
        expectVs(1)
        dut.clock.step()
        dut.io.completionEvent.expect((!redirect).B)
        expectVs(if (changed) 3 else 1)
        dut.clock.step(3)
        dut.io.completionEvent.expect(false.B)
        expectVs(if (changed) 3 else 1)
      }
    }
  }
}
