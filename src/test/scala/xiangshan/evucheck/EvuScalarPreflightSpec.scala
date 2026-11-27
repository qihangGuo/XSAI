package xiangshan.evucheck

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import top.ArgParser
import xiangshan.{ExceptionNO, ExternalVectorKey, Redirect, XSCoreParamsKey, XSTileKey}
import xiangshan.backend.Bundles.ExuOutput
import xiangshan.backend.EvuScalarPreflight
import xiangshan.backend.rob.RobPtr
import xiangshan.evu._

class ScalarPreflightProbe(implicit p: Parameters) extends Module {
  private val ep = p(ExternalVectorKey)
  private val bp = p(XSCoreParamsKey).backendParams
  bp.allSchdParams.foreach(_.bindBackendParam(bp))
  bp.allIssueParams.foreach(_.bindBackendParam(bp))
  bp.allExuParams.foreach(_.bindBackendParam(bp))
  private val exu = bp.allExuParams.find(_.name == "MRELEASE").get
  val io = IO(new Bundle {
    val reserve = Flipped(Valid(new RobPtr))
    val in = Flipped(Decoupled(new Bundle {
      val tag = new RobPtr
      val op = UInt(7.W)
      val base = UInt(64.W)
      val matrix = Bool()
    }))
    val vl = Input(UInt(8.W))
    val permitted = Input(Bool())
    val physicalStore = Input(Bool())
    val configReady = Input(Bool())
    val barrier = Input(new EvuPreflightReasons)
    val head = Input(new RobPtr)
    val flush = Flipped(Valid(new RobPtr))
    val range = new EvuRangeIO(ep)
    val out = Decoupled(new Bundle {
      val tag = new RobPtr
      val cmd = new EvuCoreCommand(ep)
      val faults = UInt(24.W)
      val evu = Bool()
    })
  })
  val source = Wire(Decoupled(new ExuOutput(exu)))
  val sink = Wire(Decoupled(new ExuOutput(exu)))
  val cmd = WireDefault(0.U.asTypeOf(new EvuCoreCommand(ep)))
  cmd.instr := Cat(io.in.bits.op, 0.U(10.W), 2.U(3.W), 0.U(5.W), EvuIsa.custom0)
  cmd.base := io.in.bits.base
  cmd.length := ep.vlenBytes.U
  source.bits := 0.U.asTypeOf(source.bits)
  source.bits.robIdx := io.in.bits.tag
  source.bits.amuCtrl.get.data := cmd.asUInt
  source.bits.amuCtrl.get.evu := !io.in.bits.matrix
  source.valid := io.in.valid
  io.in.ready := source.ready
  val csr = WireDefault(0.U.asTypeOf(new EvuCSRCoreIO(ep, (new RobPtr).getWidth)))
  csr.configured := true.B
  csr.config.valid := true.B
  csr.config.configured := true.B
  csr.config.vtype := 0x10.U
  csr.config.vl := io.vl
  csr.permitted := io.permitted
  csr.preflightConfigReady := io.configReady
  val redirect = WireDefault(0.U.asTypeOf(Valid(new Redirect)))
  redirect.valid := io.flush.valid
  redirect.bits.robIdx := io.flush.bits
  redirect.bits.level := 1.U
  EvuScalarPreflight.connect(source, sink, io.range, csr, io.reserve, io.head, redirect,
    io.barrier, reset.asBool, io.physicalStore)
  sink.ready := io.out.ready
  io.out.valid := sink.valid
  io.out.bits.tag := sink.bits.robIdx
  io.out.bits.cmd := sink.bits.amuCtrl.get.data.asTypeOf(new EvuCoreCommand(ep))
  io.out.bits.faults := sink.bits.exceptionVec.get.asUInt
  io.out.bits.evu := sink.bits.amuCtrl.get.evu
}

class EvuScalarPreflightSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "route concurrent captures and direct writes, retry a surviving check and report only the head fault" in {
    val (base, _, _) = ArgParser.parse(Array("--config", "DefaultExternalVectorMatrixConfig",
      "--disable-perf", "--disable-alwaysdb"))
    implicit val p: Parameters = base.alterPartial {
      case XSCoreParamsKey => base(XSTileKey).head.copy(RobSize = 32)
      case ExternalVectorKey => base(ExternalVectorKey).copy(enableDifftest = false)
    }
    test(new ScalarPreflightProbe).withAnnotations(Seq(TreadleBackendAnnotation)) { d =>
      val i = d.io
      i.barrier.elements.values.foreach(_.asInstanceOf[Bool].poke(false.B))
      i.configReady.poke(true.B); i.physicalStore.poke(false.B)
      i.reserve.valid.poke(false.B); i.in.valid.poke(false.B); i.flush.valid.poke(false.B)
      i.in.bits.matrix.poke(false.B); i.permitted.poke(true.B); i.vl.poke(64.U)
      i.out.ready.poke(true.B); i.range.req.ready.poke(true.B); i.range.resp.valid.poke(false.B)
      i.head.flag.poke(false.B); i.head.value.poke(1.U)
      def reserve(tag: Int): Unit = {
        i.reserve.bits.flag.poke(false.B); i.reserve.bits.value.poke(tag.U); i.reserve.valid.poke(true.B)
        d.clock.step(); i.reserve.valid.poke(false.B)
      }
      def present(tag: Int, op: Int, addr: Int): Unit = {
        i.in.bits.tag.flag.poke(false.B); i.in.bits.tag.value.poke(tag.U); i.in.bits.op.poke(op.U)
        i.in.bits.base.poke(addr.U); i.in.valid.poke(true.B)
      }
      def capture(tag: Int, addr: Int, op: Int = 0x10): Unit = {
        present(tag, op, addr); i.in.ready.expect(true.B); i.out.valid.expect(false.B)
        d.clock.step(); i.in.valid.poke(false.B)
      }
      reserve(2); reserve(3); capture(3, 0x2000, 0x52); capture(2, 0x1fc0)
      i.range.req.valid.expect(false.B); i.head.value.poke(2.U)
      i.range.req.valid.expect(true.B); i.range.req.bits.base.expect(0x1fc0.U)
      i.range.req.bits.length.expect(256.U); d.clock.step()
      present(4, 0x32, 0); i.in.ready.expect(true.B); i.out.valid.expect(true.B)
      i.out.bits.tag.value.expect(4.U); d.clock.step(); i.in.valid.poke(false.B)
      // Kill tag 3, cancel the check in flight for surviving tag 2, retry only 2.
      i.flush.bits.flag.poke(false.B); i.flush.bits.value.poke(3.U); i.flush.valid.poke(true.B)
      i.range.cancel.expect(true.B); i.range.resp.valid.poke(true.B)
      i.range.resp.ready.expect(false.B); i.out.valid.expect(false.B); d.clock.step()
      i.flush.valid.poke(false.B); i.range.resp.valid.poke(false.B)
      i.range.req.valid.expect(true.B); i.range.req.bits.base.expect(0x1fc0.U); d.clock.step()
      reserve(3); i.range.resp.bits.fault.poke(EvuAddressFault.Page)
      i.range.resp.bits.write.poke(false.B); i.range.resp.valid.poke(true.B); i.out.ready.poke(false.B)
      present(3, 0x10, 0x3000); i.in.ready.expect(true.B)
      i.range.resp.ready.expect(false.B); i.out.bits.tag.value.expect(2.U); d.clock.step()
      i.in.valid.poke(false.B); i.out.ready.poke(true.B); i.range.resp.ready.expect(true.B)
      i.out.bits.faults.expect((BigInt(1) << ExceptionNO.loadPageFault).U)
      i.out.bits.cmd.config.vl.expect(64.U); d.clock.step(); i.range.resp.valid.poke(false.B)
      i.flush.valid.poke(true.B); d.clock.step(); i.flush.valid.poke(false.B)
      i.head.value.poke(3.U); i.range.req.valid.expect(false.B)
      // VL=0 is still a real checked command. Matrix traffic on the same EXU
      // must not acquire EVU permission faults or have its payload rewritten.
      i.vl.poke(0.U); reserve(3); capture(3, 0x4000)
      i.range.req.valid.expect(true.B); i.range.req.bits.length.expect(0.U); d.clock.step()
      i.range.resp.bits.fault.poke(EvuAddressFault.None); i.range.resp.valid.poke(true.B)
      i.out.valid.expect(true.B); i.out.bits.cmd.config.vl.expect(0.U); d.clock.step()
      i.range.resp.valid.poke(false.B); i.permitted.poke(false.B)
      i.in.bits.matrix.poke(true.B); present(4, 0x10, 0x5000); i.in.ready.expect(true.B)
      i.out.valid.expect(true.B); i.out.bits.evu.expect(false.B); i.out.bits.faults.expect(0.U)
      i.out.bits.cmd.base.expect(0x5000.U); i.range.req.valid.expect(false.B)
      i.in.valid.poke(false.B); i.in.bits.matrix.poke(false.B)
      i.permitted.poke(true.B); i.head.value.poke(5.U); reserve(5); capture(5, 0x5400)
      i.range.req.valid.expect(true.B); d.clock.step()
      i.permitted.poke(false.B); i.range.resp.valid.poke(true.B); i.range.cancel.expect(true.B)
      i.range.resp.ready.expect(false.B); i.out.valid.expect(false.B); d.clock.step()
      i.range.resp.valid.poke(false.B); i.out.valid.expect(true.B)
      i.out.bits.tag.value.expect(5.U); i.out.bits.faults.expect((BigInt(1) << ExceptionNO.illegalInstr).U)
      d.clock.step(); i.permitted.poke(true.B); i.vl.poke(16.U)
      // Two scalar constants can arrive out of order, without monopolizing
      // the scalar port or starting a check before their matching ROB head.
      reserve(6); reserve(7); capture(7, 0x6004, 0x52); capture(6, 0x6000, 0x52)
      present(8, 0x32, 0); i.in.ready.expect(true.B); i.out.bits.tag.value.expect(8.U)
      i.out.valid.expect(true.B); i.range.req.valid.expect(false.B); d.clock.step(); i.in.valid.poke(false.B)
      for ((tag, addr, bytes) <- Seq((6, 0x6000, 4), (7, 0x6004, 4), (9, 0x7000, 0))) {
        if (tag == 9) { i.vl.poke(0.U); reserve(tag); capture(tag, addr, 0x52) }
        i.range.req.valid.expect(false.B); i.head.value.poke(tag.U)
        i.range.req.valid.expect(true.B); i.range.req.bits.read.expect(true.B)
        i.range.req.bits.base.expect(addr.U); i.range.req.bits.length.expect(bytes.U); d.clock.step()
        i.range.resp.valid.poke(true.B); i.range.resp.ready.expect(true.B)
        i.out.valid.expect(true.B); i.out.bits.tag.value.expect(tag.U)
        i.out.bits.cmd.base.expect(addr.U); i.out.bits.cmd.config.vl.expect((if (bytes == 0) 0 else 16).U)
        d.clock.step(); i.range.resp.valid.poke(false.B)
      }
    }
  }

  it should "start only full-line physical RAM stores early and hold a fault behind the original barrier" in {
    val (base, _, _) = ArgParser.parse(Array("--config", "DefaultExternalVectorMatrixConfig",
      "--disable-perf", "--disable-alwaysdb"))
    implicit val p: Parameters = base.alterPartial {
      case XSCoreParamsKey => base(XSTileKey).head.copy(RobSize = 32)
      case ExternalVectorKey => base(ExternalVectorKey).copy(enableDifftest = false)
    }
    test(new ScalarPreflightProbe).withAnnotations(Seq(TreadleBackendAnnotation)) { d =>
      val i = d.io
      i.reserve.valid.poke(false.B); i.flush.valid.poke(false.B); i.in.valid.poke(true.B)
      i.head.flag.poke(false.B); i.head.value.poke(3.U)
      i.in.bits.tag.flag.poke(false.B); i.in.bits.tag.value.poke(3.U)
      i.in.bits.matrix.poke(false.B); i.in.bits.op.poke(EvuIsa.store)
      i.physicalStore.poke(true.B); i.permitted.poke(true.B); i.configReady.poke(true.B)
      i.range.resp.valid.poke(false.B); i.range.req.ready.poke(true.B); i.out.ready.poke(true.B)
      i.barrier.elements.values.foreach(_.asInstanceOf[Bool].poke(false.B))
      i.barrier.status.poke(true.B); i.barrier.arithmeticOnly.poke(true.B)
      for ((addr, vl) <- Seq((0x1000L, 16), (0x80000004L, 16), (0x80000fc0L, 32),
          (0x80000040L, 15), (0x80000040L, 0))) {
        i.in.bits.base.poke(addr.U); i.vl.poke(vl.U)
        i.range.req.valid.expect(false.B); i.in.ready.expect(false.B)
      }
      i.in.bits.base.poke(0x80000fc0L.U); i.vl.poke(16.U)
      i.in.bits.op.poke(EvuIsa.storeE0); i.range.req.valid.expect(false.B); i.in.bits.op.poke(EvuIsa.store)
      i.range.req.valid.expect(true.B) // Original arithmetic-status-only bypass.
      i.barrier.control.poke(true.B) // Only a published immediate configuration is live.
      for (status <- Seq(false, true)) {
        i.barrier.status.poke(status.B); i.barrier.arithmeticOnly.poke(status.B)
        i.range.req.valid.expect(true.B)
        for (block <- Seq(i.barrier.otherCompletion, i.barrier.unpublishedControl)) {
          block.poke(true.B); i.range.req.valid.expect(false.B); block.poke(false.B)
        }
      }
      for (allow <- Seq(i.barrier.arithmeticOnly, i.configReady, i.physicalStore)) {
        allow.poke(false.B); i.range.req.valid.expect(false.B); allow.poke(true.B)
      }
      i.barrier.status.poke(false.B); i.barrier.arithmeticOnly.poke(false.B)
      i.range.req.ready.poke(false.B); i.range.req.valid.expect(true.B); d.clock.step(2)
      i.range.req.ready.poke(true.B); i.range.req.valid.expect(true.B); i.range.req.bits.write.expect(true.B)
      i.range.req.bits.fullLineWrite.expect(true.B); i.range.req.bits.length.expect(64.U)
      i.in.ready.expect(false.B); i.out.valid.expect(false.B); d.clock.step()
      i.range.resp.valid.poke(true.B); i.range.resp.bits.write.poke(true.B)
      i.range.resp.bits.fault.poke(EvuAddressFault.Access)
      i.range.resp.ready.expect(false.B); i.out.valid.expect(false.B); d.clock.step(2)
      i.barrier.control.poke(false.B); i.range.resp.ready.expect(true.B); i.out.valid.expect(true.B)
      i.out.bits.faults.expect((BigInt(1) << ExceptionNO.storeAccessFault).U)
      d.clock.step(); i.range.resp.valid.poke(false.B)
      i.barrier.control.poke(true.B); i.barrier.status.poke(true.B); i.barrier.arithmeticOnly.poke(true.B)
      i.range.req.valid.expect(true.B); d.clock.step()
      i.range.resp.bits.fault.poke(EvuAddressFault.None); i.range.resp.valid.poke(true.B)
      i.vl.poke(64.U) // The successful response still uses the request-time VL.
      i.out.valid.expect(true.B); i.out.bits.faults.expect(0.U)
      i.out.bits.cmd.config.vl.expect(16.U); i.out.bits.cmd.base.expect(0x80000fc0L.U)
      i.flush.bits.flag.poke(false.B); i.flush.bits.value.poke(3.U); i.flush.valid.poke(true.B)
      i.range.cancel.expect(true.B); i.out.valid.expect(false.B); i.range.resp.ready.expect(false.B)
      d.clock.step(); i.flush.valid.poke(false.B); i.in.valid.poke(false.B)
      i.range.resp.ready.expect(false.B); i.out.valid.expect(false.B)
    }
  }
}
