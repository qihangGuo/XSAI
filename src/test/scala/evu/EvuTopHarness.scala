package evu

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu._
import xscache.coupledL2.MatrixKey

class EvuTopHarness(val ep: EvuParams, beatBytes: Int = 64)(implicit p: Parameters) extends LazyModule {
  val evu = LazyModule(new EvuTop(ep))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(
    managers = Seq(TLSlaveParameters.v1(
      address = Seq(AddressSet(0, 0xffff)),
      regionType = RegionType.UNCACHED,
      supportsGet = TransferSizes(1, 64),
      supportsPutFull = TransferSizes(1, 64),
      mayDenyGet = true,
      mayDenyPut = true
    )),
    beatBytes = beatBytes,
    requestKeys = Seq(MatrixKey)
  )))
  sink := evu.visibilityNode

  lazy val module = new EvuTopHarnessImp(this)
}

class EvuTopHarnessImp(outer: EvuTopHarness)(implicit p: Parameters) extends LazyModuleImp(outer) {
  val (bus, edge) = outer.sink.in.head
  val io = IO(new Bundle {
    val dispatch   = Flipped(Decoupled(new EvuDispatchRequest(outer.ep)))
    val completion = Decoupled(new EvuCompletion(outer.ep))
    val matrixInput = Flipped(Decoupled(new MatrixMicroBlock(outer.ep)))
    val matrixOutput = Decoupled(UInt(outer.ep.dlenBits.W))
    val flush      = Input(Bool())
    val a          = Decoupled(new TLBundleA(edge.bundle))
    val d          = Flipped(Decoupled(new TLBundleD(edge.bundle)))
  })

  outer.evu.module.io.dispatch <> io.dispatch
  outer.evu.module.io.dispatch.bits.config := 0.U.asTypeOf(outer.evu.module.io.dispatch.bits.config)
  outer.evu.module.io.command.valid := false.B
  outer.evu.module.io.command.bits  := DontCare
  io.completion <> outer.evu.module.io.completion
  outer.evu.module.io.cuteWrite.ready := true.B
  outer.evu.module.io.matrixInput <> io.matrixInput
  io.matrixOutput <> outer.evu.module.io.matrixOutput
  outer.evu.module.io.drain.request.valid := false.B
  outer.evu.module.io.drain.request.bits := false.B
  outer.evu.module.io.drain.response.ready := true.B
  outer.evu.module.io.flush := io.flush
  io.a <> bus.a
  bus.d <> io.d
  bus.b.valid := false.B
  bus.b.bits  := DontCare
  bus.c.ready := true.B
  bus.e.ready := true.B
}
