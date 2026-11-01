package xiangshan.evu.area

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import xscache.coupledL2.MatrixKey
import xiangshan.evu._

/** Synthesis boundary, not a traffic generator or memory model. */
class EvuAreaTop(val ep: EvuParams, val withBridge: Boolean = false,
    val keepPerf: Boolean = false, val addressBits: Int = 48, val tileDim: Int = 8)
    (implicit p: Parameters) extends LazyModule {
  require(!ep.enableDifftest, "Area export must not instantiate the debug VRF shadow")
  require(addressBits >= 32 && addressBits <= 56)
  require(tileDim >= 8 && (tileDim & (tileDim - 1)) == 0)
  val evu = LazyModule(new EvuTop(ep))
  val boundary = TLManagerNode(Seq(TLSlavePortParameters.v1(
    Seq(TLSlaveParameters.v1(
      address = Seq(AddressSet(0, (BigInt(1) << addressBits) - 1)),
      regionType = RegionType.UNCACHED,
      supportsGet = TransferSizes(1, 64), supportsPutFull = TransferSizes(1, 64),
      mayDenyGet = true, mayDenyPut = true)),
    beatBytes = 64, requestKeys = Seq(MatrixKey))))
  boundary := evu.visibilityNode
  lazy val module = new EvuAreaTopImp(this)
}

class EvuAreaTopImp(outer: EvuAreaTop)(implicit p: Parameters) extends LazyModuleImp(outer) {
  override def desiredName: String = "EvuAreaTop"
  private val ep = outer.ep
  private val (bus, edge) = outer.boundary.in.head
  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new EvuDispatchRequest(ep)))
    val completion = Decoupled(new EvuCompletion(ep))
    val drain = Flipped(new EvuDrainIO)
    val flush = Input(Bool())
    val busy = Output(Bool())
    val cuteWrite = Decoupled(new EvuCuteWrite(ep))
    val matrixInput = Option.when(!outer.withBridge)(Flipped(Decoupled(new MatrixMicroBlock(ep))))
    val tileInput = Option.when(outer.withBridge)(Flipped(Decoupled(new MatrixTileResult(outer.tileDim * outer.tileDim * 32))))
    val jobStatus = Output(Vec(ep.commandDepth, UInt(3.W)))
    val jobEpoch = Output(UInt(2.W))
    val matrixScaleTag = Output(UInt(ep.regIndexWidth.W))
    val matrixBiasTag = Output(UInt(ep.regIndexWidth.W))
    val perf = Option.when(outer.keepPerf)(Output(new EvuPerf))
    val a = Decoupled(new TLBundleA(edge.bundle))
    val d = Flipped(Decoupled(new TLBundleD(edge.bundle)))
  })
  val evu = outer.evu.module.io
  evu.dispatch <> io.dispatch
  // The raw command input and unused matrix tap match the production tile.
  evu.command.valid := false.B
  evu.command.bits := 0.U.asTypeOf(evu.command.bits)
  evu.matrixOutput.ready := true.B
  io.completion <> evu.completion
  evu.drain <> io.drain
  evu.flush := io.flush
  io.busy := evu.busy
  io.cuteWrite <> evu.cuteWrite
  io.jobStatus := evu.jobStatus
  io.jobEpoch := evu.jobEpoch
  io.matrixScaleTag := evu.matrixScaleTag
  io.matrixBiasTag := evu.matrixBiasTag
  io.perf.foreach(_ := evu.perf)
  if (outer.withBridge) {
    val bridge = Module(new MatrixVectorBridge(outer.tileDim * outer.tileDim * 32, ep))
    bridge.io.in <> io.tileInput.get
    evu.matrixInput <> bridge.io.out
  } else {
    evu.matrixInput <> io.matrixInput.get
  }
  io.a <> bus.a
  bus.d <> io.d
  bus.b.valid := false.B
  bus.b.bits := 0.U.asTypeOf(bus.b.bits)
  bus.c.ready := true.B
  bus.e.ready := true.B
}
