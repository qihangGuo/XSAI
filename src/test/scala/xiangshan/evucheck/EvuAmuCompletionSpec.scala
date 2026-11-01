package xiangshan.evucheck

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import top.ArgParser
import xiangshan.{ExternalVectorKey, XSCoreParamsKey, XSTileKey}
import xiangshan.backend.BackendParams
import xiangshan.backend.fu.matrix.Bundles.AmuCtrlIO
import xiangshan.backend.rob.AmuCtrlBuffer
import xiangshan.evu.{EvuCompletion, EvuCoreCommand}

class EvuAmuCompletionHarness(implicit p: Parameters) extends Module {
  private implicit val backend: BackendParams = p(XSCoreParamsKey).backendParams
  backend.allSchdParams.foreach(_.bindBackendParam(backend))
  backend.allIssueParams.foreach(_.bindBackendParam(backend))
  backend.allExuParams.foreach(_.bindBackendParam(backend))
  private val ep = p(ExternalVectorKey)
  val io = IO(new Bundle {
    val index = Input(UInt(5.W))
    val enqueue = Input(Bool())
    val writeback = Input(Bool())
    val commit = Input(Bool())
    val needAmu = Input(Bool())
    val pc = Input(UInt(64.W))
    val instr = Input(UInt(32.W))
    val issueReady = Input(Bool())
    val issued = Output(Valid(new Bundle {
      val pc = UInt(64.W)
      val instr = UInt(32.W)
    }))
    val completion = Flipped(Decoupled(new EvuCompletion(ep)))
    val status = Output(Valid(new EvuCompletion(ep)))
    val redirect = Input(Bool())
    val busy = Output(Bool())
    val committedBusy = Output(Bool())
    val preflightBusy = Output(Bool())
  })
  val amu = Module(new AmuCtrlBuffer)
  amu.io.enq := 0.U.asTypeOf(amu.io.enq)
  amu.io.enq(0).valid := io.enqueue
  amu.io.enq(0).reqValid := io.enqueue
  amu.io.enq(0).allocPtr.value := io.index
  amu.io.enq(0).needAMU := io.needAmu
  amu.io.wb := 0.U.asTypeOf(amu.io.wb)
  val command = WireDefault(0.U.asTypeOf(new EvuCoreCommand(ep)))
  command.pc := io.pc
  command.instr := io.instr
  val wb = amu.io.wb.find(_.bits.amuCtrl.nonEmpty).get
  wb.valid := io.writeback
  wb.bits.robIdx.value := io.index
  wb.bits.amuCtrl.get.evu := true.B
  wb.bits.amuCtrl.get.data := command.asUInt
  amu.io.deqCommit := 0.U.asTypeOf(amu.io.deqCommit)
  amu.io.deqCommit(0).valid := io.commit
  amu.io.deqCommit(0).ptr.value := io.index
  amu.io.redirect := 0.U.asTypeOf(amu.io.redirect)
  amu.io.redirect.valid := io.redirect
  amu.io.redirect.all := io.redirect
  amu.io.hartId.foreach(_ := 0.U)
  // Match the core's single, oldest-first AMU dispatch handshake.
  val issue = Module(new Arbiter(new AmuCtrlIO, amu.io.toAMU.length))
  issue.io.in <> amu.io.toAMU
  issue.io.out.ready := io.issueReady
  val issued = issue.io.out.bits.data.asTypeOf(new EvuCoreCommand(ep))
  io.issued.valid := issue.io.out.fire
  io.issued.bits.pc := issued.pc
  io.issued.bits.instr := issued.instr
  amu.io.evuCompletion.get <> io.completion
  io.status := amu.io.evuStatus.get
  io.busy := amu.io.evuBusy
  io.committedBusy := amu.io.evuCommittedBusy
  io.preflightBusy := amu.io.evuPreflightBusy
}

class EvuAmuCompletionSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AMU ordered EVU completions"

  it should "drain retired memory before traps without waiting for a faulting uncommitted instruction" in {
    val (base, _, _) = ArgParser.parse(Array("--config", "DefaultExternalVectorMatrixConfig",
      "--disable-perf", "--disable-alwaysdb"))
    implicit val p: Parameters = base.alterPartial {
      case XSCoreParamsKey => base(XSTileKey).head.copy(RobSize = 32)
    }
    test(new EvuAmuCompletionHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.index.poke(0.U)
      dut.io.enqueue.poke(false.B)
      dut.io.writeback.poke(false.B)
      dut.io.commit.poke(false.B)
      dut.io.needAmu.poke(true.B)
      dut.io.pc.poke(0x80004000L.U)
      dut.io.instr.poke(((BigInt(0x53) << 25) | 0xb).U)
      dut.io.issueReady.poke(false.B)
      dut.io.redirect.poke(false.B)
      dut.io.completion.valid.poke(false.B)
      def clear(x: Data): Unit = x match {
        case b: Bool => b.poke(false.B)
        case u: UInt => u.poke(0.U)
        case v: Vec[_] => v.foreach(clear)
        case b: Bundle => b.elements.values.foreach(clear)
      }
      clear(dut.io.completion.bits)
      def writeback(index: Int): Unit = {
        dut.io.index.poke(index.U)
        dut.io.enqueue.poke(true.B)
        dut.clock.step()
        dut.io.enqueue.poke(false.B)
        dut.io.writeback.poke(true.B)
        dut.clock.step()
        dut.io.writeback.poke(false.B)
      }
      writeback(0)
      dut.io.busy.expect(true.B)
      dut.io.committedBusy.expect(false.B)
      dut.io.commit.poke(true.B)
      dut.clock.step()
      dut.io.commit.poke(false.B)
      dut.io.committedBusy.expect(true.B) // Retired but not yet accepted also counts.
      dut.io.issueReady.poke(true.B)
      dut.io.issued.valid.expect(true.B)
      dut.clock.step()
      writeback(1) // A later preflight fault never commits or reaches the EVU.
      dut.io.issued.valid.expect(false.B)
      dut.io.committedBusy.expect(true.B)
      dut.io.completion.valid.poke(true.B)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.io.committedBusy.expect(true.B) // Include the CSR status propagation.
      dut.clock.step(5)
      dut.io.committedBusy.expect(false.B)
      dut.io.busy.expect(true.B) // The fault itself must not keep the trap blocked.
      dut.io.issued.valid.expect(false.B)
    }
  }

  it should "resume after a fence when retirement has passed scalar entries" in {
    val (base, _, _) = ArgParser.parse(Array("--config", "DefaultExternalVectorMatrixConfig",
      "--disable-perf", "--disable-alwaysdb"))
    implicit val p: Parameters = base.alterPartial {
      case XSCoreParamsKey => base(XSTileKey).head.copy(RobSize = 32)
    }
    test(new EvuAmuCompletionHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.index.poke(0.U)
      dut.io.enqueue.poke(false.B)
      dut.io.writeback.poke(false.B)
      dut.io.commit.poke(false.B)
      dut.io.needAmu.poke(true.B)
      dut.io.pc.poke(0.U)
      dut.io.instr.poke(0.U)
      dut.io.issueReady.poke(true.B)
      dut.io.redirect.poke(false.B)
      dut.io.completion.valid.poke(false.B)
      def clear(x: Data): Unit = x match {
        case b: Bool => b.poke(false.B)
        case u: UInt => u.poke(0.U)
        case v: Vec[_] => v.foreach(clear)
        case b: Bundle => b.elements.values.foreach(clear)
      }
      clear(dut.io.completion.bits)
      def prepare(index: Int, needAmu: Boolean, instr: BigInt): Unit = {
        dut.io.index.poke(index.U)
        dut.io.pc.poke((0x80003000L + index * 4).U)
        dut.io.instr.poke(instr.U)
        dut.io.needAmu.poke(needAmu.B)
        dut.io.enqueue.poke(true.B)
        dut.clock.step()
        dut.io.enqueue.poke(false.B)
        if (needAmu) {
          dut.io.writeback.poke(true.B)
          dut.clock.step()
          dut.io.writeback.poke(false.B)
        }
        dut.io.commit.poke(true.B)
        dut.clock.step()
        dut.io.commit.poke(false.B)
      }
      prepare(0, true, BigInt("3400000b", 16))
      dut.io.issued.valid.expect(true.B)
      dut.clock.step()
      for (index <- 1 to 12) prepare(index, false, 0)
      prepare(13, true, (BigInt(1) << 25) | 0xb)
      dut.io.issued.valid.expect(false.B)
      dut.io.completion.valid.poke(true.B)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      var issued = false
      for (_ <- 0 until 20) {
        if (dut.io.issued.valid.peek().litToBoolean) {
          issued shouldBe false
          dut.io.issued.bits.pc.expect(0x80003034L.U)
          issued = true
        }
        dut.clock.step()
      }
      issued shouldBe true
    }
  }

  it should "issue beyond the retirement window while an old EVU command is unfinished" in {
    val (base, _, _) = ArgParser.parse(Array("--config", "DefaultExternalVectorMatrixConfig",
      "--disable-perf", "--disable-alwaysdb"))
    implicit val p: Parameters = base.alterPartial {
      case XSCoreParamsKey => base(XSTileKey).head.copy(RobSize = 32)
    }
    test(new EvuAmuCompletionHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.index.poke(0.U)
      dut.io.enqueue.poke(false.B)
      dut.io.writeback.poke(false.B)
      dut.io.commit.poke(false.B)
      dut.io.needAmu.poke(true.B)
      dut.io.pc.poke(0.U)
      dut.io.instr.poke(0.U)
      dut.io.issueReady.poke(false.B)
      dut.io.redirect.poke(false.B)
      dut.io.completion.valid.poke(false.B)
      for (index <- 0 until 20) {
        dut.io.index.poke(index.U)
        dut.io.pc.poke((0x80002000L + index * 4).U)
        dut.io.instr.poke(((BigInt(1) << 25) | 0xb).U)
        dut.io.enqueue.poke(true.B)
        dut.clock.step()
        dut.io.enqueue.poke(false.B)
        dut.io.writeback.poke(true.B)
        dut.clock.step()
        dut.io.writeback.poke(false.B)
        dut.io.commit.poke(true.B)
        dut.clock.step()
        dut.io.commit.poke(false.B)
      }
      dut.io.issueReady.poke(true.B)
      var issued = 0
      for (_ <- 0 until 35) {
        if (dut.io.issued.valid.peek().litToBoolean) {
          dut.io.issued.bits.pc.expect((0x80002000L + issued * 4).U)
          issued += 1
        }
        dut.clock.step()
      }
      issued shouldBe 20
      dut.io.busy.expect(true.B)
      dut.io.completion.ready.expect(true.B)
      dut.io.issued.valid.expect(false.B)
    }
  }

  it should "consume quant and fence completions once across dequeue bubbles and redirects" in {
    val (base, _, _) = ArgParser.parse(Array("--config", "DefaultExternalVectorMatrixConfig",
      "--disable-perf", "--disable-alwaysdb"))
    implicit val p: Parameters = base.alterPartial {
      case XSCoreParamsKey => base(XSTileKey).head.copy(RobSize = 32)
    }
    test(new EvuAmuCompletionHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val quantPc = BigInt("80001100", 16)
      val fencePc = BigInt("8000113a", 16)
      val quantInstr = BigInt("a055a68b", 16)
      val fenceInstr = BigInt("3400000b", 16)
      def completion(pc: BigInt, opcode: Int, flags: Int, fault: Boolean = false): Unit = {
        val c = dut.io.completion.bits
        c.pc.poke(pc.U)
        c.tval.poke(0.U)
        c.tvalValid.poke(false.B)
        c.opcode.poke(opcode.U)
        c.vd.poke(0.U)
        c.exception.poke(fault.B)
        c.fflags.poke(flags.U)
        c.vxsat.poke(false.B)
        c.dirtyVs.poke(false.B)
        c.cause.poke((if (fault) 6 else 0).U)
        c.jobId.poke((if (opcode == 0x50) 0 else 1).U)
        c.epoch.poke(0.U)
      }
      def reset(): Unit = {
        dut.io.index.poke(0.U)
        dut.io.enqueue.poke(false.B)
        dut.io.writeback.poke(false.B)
        dut.io.commit.poke(false.B)
        dut.io.needAmu.poke(true.B)
        dut.io.pc.poke(0.U)
        dut.io.instr.poke(0.U)
        dut.io.issueReady.poke(false.B)
        dut.io.redirect.poke(false.B)
        dut.io.completion.valid.poke(false.B)
        completion(quantPc, 0x50, 1)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
      }
      def prepare(index: Int, pc: BigInt, instr: BigInt, needAmu: Boolean = true): Unit = {
        dut.io.index.poke(index.U)
        dut.io.pc.poke(pc.U)
        dut.io.instr.poke(instr.U)
        dut.io.needAmu.poke(needAmu.B)
        dut.io.enqueue.poke(true.B)
        dut.clock.step()
        dut.io.enqueue.poke(false.B)
        if (needAmu) {
          dut.io.writeback.poke(true.B)
          dut.clock.step()
          dut.io.writeback.poke(false.B)
        }
        dut.io.commit.poke(true.B)
        dut.clock.step()
        dut.io.commit.poke(false.B)
      }
      def expectStatus(pc: BigInt, opcode: Int, flags: Int, fault: Boolean = false): Unit = {
        dut.io.status.valid.expect(true.B)
        dut.io.status.bits.pc.expect(pc.U)
        dut.io.status.bits.opcode.expect(opcode.U)
        dut.io.status.bits.fflags.expect(flags.U)
        dut.io.status.bits.exception.expect(fault.B)
        dut.io.status.bits.cause.expect((if (fault) 6 else 0).U)
      }
      for ((scalarGap, fault) <- Seq((false, false), (true, false), (false, true))) {
        reset()
        prepare(0, quantPc, quantInstr)
        if (scalarGap) prepare(1, 0, 0, needAmu = false)
        prepare(if (scalarGap) 2 else 1, fencePc, fenceInstr)
        // An offered completion cannot bypass command acceptance.
        dut.io.completion.valid.poke(true.B)
        dut.io.completion.ready.expect(false.B)
        dut.clock.step(2)
        dut.io.status.valid.expect(false.B)
        dut.io.completion.valid.poke(false.B)
        dut.io.issueReady.poke(true.B)
        for ((pc, instr) <- Seq((quantPc, quantInstr), (fencePc, fenceInstr))) {
          dut.io.issued.valid.expect(true.B)
          dut.io.issued.bits.pc.expect(pc.U)
          dut.io.issued.bits.instr.expect(instr.U)
          dut.clock.step()
        }
        dut.io.issued.valid.expect(false.B)
        dut.io.completion.ready.expect(true.B)
        dut.io.completion.valid.poke(true.B)
        dut.clock.step() // Quant completes, but its AMU entry is not yet dequeued.
        expectStatus(quantPc, 0x50, 1)
        // Keep valid asserted: FENCE is ready immediately behind quant.
        completion(fencePc, 0x1a, 16, fault)
        dut.io.completion.ready.expect(false.B)
        dut.clock.step() // Dequeue the completed quant (and any scalar gap).
        dut.io.status.valid.expect(false.B)
        dut.io.completion.ready.expect(true.B)
        dut.clock.step()
        expectStatus(fencePc, 0x1a, 16, fault)
        dut.io.completion.valid.poke(false.B)
        dut.io.completion.ready.expect(false.B)
        dut.clock.step()
        for (_ <- 0 until 5) {
          dut.io.status.valid.expect(false.B)
          dut.io.completion.ready.expect(false.B)
          dut.clock.step()
        }
        dut.io.busy.expect(false.B)
      }
      reset()
      prepare(0, quantPc, quantInstr)
      dut.io.busy.expect(true.B)
      dut.io.preflightBusy.expect(false.B)
      dut.io.issueReady.poke(true.B)
      dut.io.issued.valid.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(true.B)
      dut.io.completion.ready.expect(true.B)
      dut.io.redirect.poke(true.B)
      dut.io.completion.ready.expect(false.B)
      dut.clock.step()
      dut.io.redirect.poke(false.B)
      for (_ <- 0 until 5) {
        dut.io.completion.ready.expect(false.B)
        dut.io.status.valid.expect(false.B)
        dut.clock.step()
      }
      dut.io.completion.valid.poke(false.B)
      dut.io.busy.expect(false.B)
      dut.io.preflightBusy.expect(false.B)
      // A completed ordinary instruction still blocks preflight while its
      // status propagates; only control blocks ordinary memory preflight.
      for (f7 <- Seq(1, 0x10, 0x11, 0x18, 0x19, 0x1a, 0x45, 0x46)) {
        reset()
        prepare(0, quantPc, (BigInt(f7) << 25) | 0xb)
        dut.io.busy.expect(true.B)
        dut.io.preflightBusy.expect((!Seq(1, 0x10, 0x11).contains(f7)).B)
        dut.io.issueReady.poke(true.B)
        dut.clock.step()
        completion(quantPc, 1, 0)
        dut.io.completion.valid.poke(true.B)
        dut.io.completion.ready.expect(true.B)
        dut.clock.step()
        dut.io.completion.valid.poke(false.B)
        dut.io.preflightBusy.expect(true.B)
        dut.clock.step(3)
        dut.io.preflightBusy.expect(true.B)
        dut.clock.step(2)
        dut.io.preflightBusy.expect(false.B)
        dut.io.busy.expect(false.B)
      }
    }
  }
}
