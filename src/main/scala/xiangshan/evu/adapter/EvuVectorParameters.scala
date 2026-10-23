package xiangshan.evu.adapter

import chisel3.Data
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.prci.ClockSinkParameters
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._
import xiangshan.evu.common.{VectorParams, VectorParamsKey}

/** Minimal CoreParams used only to size Saturn's vector data structures. */
case class XSAIVectorCoreParams(vectorLength: Int, memoryWidth: Int) extends CoreParams {
  val bootFreqHz = BigInt(0)
  val useVM = false
  val useHypervisor = false
  val useUser = false
  val useSupervisor = false
  val useDebug = false
  val useAtomics = false
  val useAtomicsOnlyForIO = false
  val useCompressed = false
  val useBitManip = false
  val useBitManipCrypto = false
  override val useVector = true
  val useCryptoNIST = false
  val useCryptoSM = false
  val useRVE = false
  val useConditionalZero = false
  val mulDiv = None
  val fpu = Some(FPUParams(fLen = 32, minFLen = 16))
  val fetchWidth = 1
  val decodeWidth = 1
  val retireWidth = 1
  val instBits = 32
  val nLocalInterrupts = 0
  val useNMI = false
  val nPMPs = 0
  val pmpGranularity = 4
  val nBreakpoints = 0
  val useBPWatch = false
  val mcontextWidth = 0
  val scontextWidth = 0
  val nPerfCounters = 0
  val haveBasicCounters = false
  val haveFSDirty = false
  val misaWritable = false
  val haveCFlush = false
  val nL2TLBEntries = 0
  val nL2TLBWays = 1
  val nPTECacheEntries = 0
  val mtvecInit = None
  val mtvecWritable = false
  val traceHasWdata = false
  val lrscCycles = 1
  override def eLen(xLen: Int, fLen: Int): Int = 32
  override def vLen = vectorLength
  override def vMemDataBits = memoryWidth
}

case class XSAIVectorTileParams(
    core: CoreParams,
    hartId: Int = 0,
    icache: Option[ICacheParams] = None,
    dcache: Option[DCacheParams] = None,
    btb: Option[BTBParams] = None,
    beuAddr: Option[BigInt] = None,
    blockerCtrlAddr: Option[BigInt] = None,
    name: Option[String] = Some("xsai-evu"),
    clockSinkParams: ClockSinkParameters = ClockSinkParameters()
) extends TileParams

object EvuVectorParameters {
  def apply(
      base: Parameters,
      vectorLength: Int,
      datapathWidth: Int,
      memoryWidth: Int,
      params: VectorParams = VectorParams.lgvParams
  ): Parameters = {
    require(vectorLength >= datapathWidth && vectorLength % datapathWidth == 0)
    require(datapathWidth >= memoryWidth && datapathWidth % memoryWidth == 0)
    base.alterPartial {
      case TileKey => XSAIVectorTileParams(XSAIVectorCoreParams(vectorLength, memoryWidth))
      case VectorParamsKey => params.copy(dLen = datapathWidth, mLen = memoryWidth)
    }
  }
}
