package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.TileVisibilityNodeKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common.VectorParams
import xiangshan.evu.frontend.VectorDispatcher

class EvuUnifiedDispatchShell(implicit p: Parameters) extends LazyModule {
  val visibility = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("unified-dispatch")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffff)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := visibility
  lazy val module = new EvuUnifiedDispatchShellImp(this)
}

class EvuUnifiedDispatchShellImp(outer: EvuUnifiedDispatchShell)(implicit p: Parameters)
    extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(5.W)))
    val out = Decoupled(UInt(5.W))
    val head = Input(UInt(5.W))
    val tail = Input(UInt(5.W))
    val observedHead = Output(UInt(5.W))
    val observedTail = Output(UInt(5.W))
    val release = Input(Valid(UInt(5.W)))
  })
  val dispatcher = Module(new VectorDispatcher()(p.alterPartial {
    case TileVisibilityNodeKey => outer.visibility
  }))
  dispatcher.io.issue.valid := io.in.valid
  io.in.ready := dispatcher.io.issue.ready
  dispatcher.io.issue.bits := 0.U.asTypeOf(dispatcher.io.issue.bits)
  dispatcher.io.issue.bits.bits := "h02000057".U // Unmasked vadd.vv.
  dispatcher.io.issue.bits.vconfig.vl := 16.U
  dispatcher.io.issue.bits.vconfig.vtype.vsew := 2.U
  dispatcher.io.issue.bits.vat := io.in.bits
  dispatcher.io.commandHead.get := io.head
  dispatcher.io.commandTail.get := io.tail
  io.observedHead := dispatcher.io.vat_head
  io.observedTail := dispatcher.io.vat_tail
  dispatcher.io.dis.ready := io.out.ready
  io.out.valid := dispatcher.io.dis.valid
  io.out.bits := dispatcher.io.dis.bits.vat
  dispatcher.io.mem.ready := true.B
  dispatcher.io.scalar_resp.ready := true.B
  dispatcher.io.vat_release.foreach { release =>
    release.valid := false.B
    release.bits := 0.U
  }
  dispatcher.io.vat_release.head := io.release
  val bus = outer.visibility.out.head._1
  bus.a.valid := false.B
  bus.a.bits := 0.U.asTypeOf(bus.a.bits)
  bus.d.ready := true.B
  val manager = outer.sink.in.head._1
  manager.a.ready := true.B
  manager.d.valid := false.B
  manager.d.bits := 0.U.asTypeOf(manager.d.bits)
}

class EvuUnifiedDispatchSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "EVU externally allocated execution age tags"

  it should "preserve tags across wrap and skipped control commands without internal reallocation" in {
    val base = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    implicit val p: Parameters = EvuVectorParameters(base, 4096, 512, 512,
      VectorParams.lgvParams.copy(externalCommandTags = true))
    test(LazyModule(new EvuUnifiedDispatchShell).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U)
      dut.io.out.ready.poke(false.B)
      dut.io.head.poke(30.U)
      dut.io.tail.poke(30.U)
      dut.io.release.valid.poke(false.B)
      dut.io.release.bits.poke(0.U)
      for (tag <- Seq(30, 31, 0, 2, 7, 16, 29)) {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.poke(tag.U)
        dut.io.tail.poke(tag.U)
        dut.io.out.ready.poke(false.B)
        dut.io.in.ready.expect(false.B)
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.expect(tag.U)
        dut.clock.step()
        dut.io.out.ready.poke(true.B)
        dut.io.in.ready.expect(true.B)
        dut.io.observedHead.expect(30.U)
        dut.io.observedTail.expect(tag.U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.io.release.valid.poke(true.B)
        dut.io.release.bits.poke(tag.U)
        dut.clock.step()
        dut.io.release.valid.poke(false.B)
      }
    }
  }
}
