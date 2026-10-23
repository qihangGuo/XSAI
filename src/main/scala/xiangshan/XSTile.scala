/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan

import org.chipsalliance.cde.config.{Config, Field, Parameters}
import chisel3._
import chisel3.util.{Valid, ValidIO, log2Up, Arbiter, Decoupled, Cat}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.tile.{BusErrorUnit, BusErrorUnitParams, BusErrors}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}
import freechips.rocketchip.amba.axi4._
import system.HasSoCParameter
import top.{ArgParser, BusPerfMonitor, Generator}
import utility._
import utility.sram.SramBroadcastBundle
import xscache.chi.{CHIAddrWidthKey, CHIDataCheckKey, CHIIssue, CHIPoisonKey, DecoupledPortIO, NonSecureKey, PortIO}
import xiangshan.backend.trace.TraceCoreInterface
import xiangshan.backend.fu.matrix._
import xiangshan.backend.fu.matrix.Bundles._
import cute.{CutePerfAsyncCrossing, CutePerfToCoreIO, XSCute}
import xiangshan.evu.{EvuCompletion, EvuCoreCommand, EvuDispatchRequest, EvuTop, MatrixVectorBridge}

object MatAcc extends Enumeration {
  type MatAcc = Value
  val AME, CUTE = Value
}

case object MatAccKey extends Field[MatAcc.Value](MatAcc.AME)

case class ExternalAcceleratorCDCParams(
  enable: Boolean = false,
  command: AsyncQueueParams = AsyncQueueParams(depth = 4, sync = 3, safe = true),
  completion: AsyncQueueParams = AsyncQueueParams(depth = 4, sync = 3, safe = true),
  matrixData: AsyncQueueParams = AsyncQueueParams(depth = 2, sync = 3, safe = true),
  tileLink: AsyncQueueParams = AsyncQueueParams(depth = 8, sync = 3, safe = true),
  perfSnapshot: AsyncQueueParams = AsyncQueueParams.singleton(sync = 3, safe = true)
)

case object ExternalAcceleratorCDCKey
  extends Field[ExternalAcceleratorCDCParams](ExternalAcceleratorCDCParams())

class XSTile()(implicit p: Parameters) extends LazyModule
  with HasXSParameter
  with HasSoCParameter
{
  override def shouldBeInlined: Boolean = false
  val core = LazyModule(new XSCore())
  val l2top = LazyModule(new L2Top())

  val cuteOpt = p(MatAccKey) match {
    case MatAcc.AME => None
    case MatAcc.CUTE => Some(LazyModule(new XSCute()))
  }
  private val acceleratorCDC = p(ExternalAcceleratorCDCKey)
  private val useAcceleratorCDC = cuteOpt.nonEmpty && acceleratorCDC.enable
  val evuOpt = Option.when(p(ExternalVectorKey).enable)(LazyModule(new EvuTop(p(ExternalVectorKey))))

  require(!p(ExternalVectorKey).enable || HasMatrixExtension,
    "EVU requires the AMU retirement interface provided by the matrix extension")
  require(coreParams.EnableExternalVector == p(ExternalVectorKey).enable,
    "Use WithExternalVector to enable both the scalar dispatch path and the EVU")

  val enableL2 = coreParams.L2CacheParamsOpt.isDefined
  // =========== Public Ports ============
  val memBlock = core.memBlock.inner
  val memory_port = None
  val tl_uncache = l2top.inner.mmio_port
  val sep_tl_opt = l2top.inner.sep_tl_port_opt
  val beu_int_source = l2top.inner.beu.intNode
  val core_reset_sink = BundleBridgeSink(Some(() => Reset()))
  val clint_int_node = l2top.inner.clint_int_node
  val plic_int_node = l2top.inner.plic_int_node
  val debug_int_node = l2top.inner.debug_int_node
  val nmi_int_node = l2top.inner.nmi_int_node
  memBlock.clint_int_sink := IntBuffer() := clint_int_node
  memBlock.plic_int_sink :*= IntBuffer() :*= plic_int_node
  memBlock.debug_int_sink := IntBuffer() := debug_int_node
  memBlock.nmi_int_sink   := IntBuffer() := nmi_int_node
  memBlock.beu_local_int_sink := l2top.inner.beu_local_int_source_buffer

  // =========== Components' Connection ============
  // L1 to l1_xbar
  coreParams.dcacheParametersOpt.map { _ =>
    l2top.inner.misc_l2_pmu := l2top.inner.l1d_logger := memBlock.dcache_port :=
      memBlock.l1d_to_l2_buffer.node := memBlock.dcache.clientNode
  }

  val cuteTLAsyncCrossings = cuteOpt.toSeq.flatMap { cute =>
    cute.node.map { node =>
      if (useAcceleratorCDC) {
        val source = LazyModule(new TLAsyncCrossingSource())
        val sink = LazyModule(new TLAsyncCrossingSink(acceleratorCDC.tileLink))
        source.node := node
        sink.node := source.node
        l2top.inner.misc_l2_pmu := l2top.inner.cute_logger := sink.node
        Some((source, sink))
      } else {
        l2top.inner.misc_l2_pmu := l2top.inner.cute_logger := node
        None
      }
    }
  }.flatten

  val evuTLAsyncCrossing = evuOpt.flatMap { evu =>
    if (useAcceleratorCDC) {
      val source = LazyModule(new TLAsyncCrossingSource())
      val sink = LazyModule(new TLAsyncCrossingSink(acceleratorCDC.tileLink))
      source.node := evu.visibilityNode
      sink.node := source.node
      l2top.inner.misc_l2_pmu := sink.node
      Some((source, sink))
    } else {
      l2top.inner.misc_l2_pmu := evu.visibilityNode
      None
    }
  }

  l2top.inner.misc_l2_pmu := l2top.inner.l1i_logger := memBlock.frontendBridge.icache_node
  if (!coreParams.softPTW) {
    l2top.inner.misc_l2_pmu := l2top.inner.ptw_logger := l2top.inner.ptw_to_l2_buffer.node := memBlock.ptw_to_l2_buffer.node
  }

  // L2 Prefetch
  l2top.inner.l2cache match {
    case Some(l2) =>
      l2.pf_recv_node.foreach(recv => {
        println("Connecting L1 prefetcher to L2!")
        recv := memBlock.l2_pf_sender_opt.get
      })
    case None =>
  }

  val core_l3_tpmeta_source_port = l2top.inner.l2cache match {
    case Some(l2) => l2.tpmeta_source_node
    case None => None
  }
  val core_l3_tpmeta_sink_port = l2top.inner.l2cache match {
    case Some(l2) => l2.tpmeta_sink_node
    case None => None
  }

  // mmio
  l2top.inner.i_mmio_port := l2top.inner.i_mmio_buffer.node := memBlock.frontendBridge.instr_uncache_node
  if (soc.EnableICacheCtrl) {
    memBlock.frontendBridge.icachectrl_node := l2top.inner.icachectrl_port_opt.get
  }
  l2top.inner.d_mmio_port := memBlock.uncache_port

  // =========== IO Connection ============
  class XSTileImp(wrapper: LazyModule) extends LazyModuleImp(wrapper) {
    val io = IO(new Bundle {
      val hartId = Input(UInt(hartIdLen.W))
      val msiInfo = Input(ValidIO(UInt(soc.IMSICParams.MSI_INFO_WIDTH.W)))
      val msiAck = Output(Bool())
      val teemsiInfo = Option.when(soc.IMSICParams.HasTEEIMSIC)(Input(ValidIO(UInt(soc.IMSICParams.MSI_INFO_WIDTH.W))))
      val teemsiAck = Option.when(soc.IMSICParams.HasTEEIMSIC)(Output(Bool()))
      val reset_vector = Input(UInt(PAddrBits.W))
      val cpu_halt = Output(Bool())
      val cpu_crtical_error = Output(Bool())
      val hartIsInReset = Output(Bool())
      val traceCoreInterface = new TraceCoreInterface
      val debugTopDown = new Bundle {
        val robHeadPaddr = Valid(UInt(PAddrBits.W))
        val l3MissMatch = Input(Bool())
      }
      val l3Miss = Input(Bool())
      val chi = Option.when(isOpenLLC)(new PortIO)
      val decoupledCHI = Option.when(isZhuJiang)(
        new DecoupledPortIO()(p.alter((_, _, _) => {
          case CHIIssue => p(CHIIssue)
          case CHIAddrWidthKey => p(CHIAddrWidthKey)
          case NonSecureKey => p(NonSecureKey)
          case CHIDataCheckKey => "none"
          case CHIPoisonKey => false
        }))
      )
      val nodeID = Some(Input(UInt(NodeIDWidth.W)))
      val clintTime = Input(ValidIO(UInt(64.W)))
      val dft = Option.when(hasDFT)(Input(new SramBroadcastBundle))
      val dft_reset = Option.when(hasMbist)(Input(new DFTResetSignals()))
      val l2_flush_en = Option.when(EnablePowerDown) (Output(Bool()))
      val l2_flush_done = Option.when(EnablePowerDown) (Output(Bool()))
      val acceleratorClock = Option.when(useAcceleratorCDC)(Input(Clock()))
    })

    dontTouch(io.hartId)
    dontTouch(io.msiInfo)
    io.teemsiInfo.foreach(dontTouch(_))
    io.chi.foreach(dontTouch(_))
    io.decoupledCHI.foreach(dontTouch(_))

    val core_soft_rst = core_reset_sink.in.head._1 // unused

    l2top.module.io.hartId.fromTile := io.hartId
    core.module.io.hartId := l2top.module.io.hartId.toCore
    core.module.io.reset_vector := l2top.module.io.reset_vector.toCore
    core.module.io.msiInfo := l2top.module.io.msiInfo.toCore
    l2top.module.io.msiInfo.fromTile := io.msiInfo
    core.module.io.teemsiInfo zip l2top.module.io.teemsiInfo foreach { case (core_teemsiInfo, l2top_teemsiInfo) =>
      core_teemsiInfo := l2top_teemsiInfo.toCore
    }
    l2top.module.io.teemsiInfo zip io.teemsiInfo foreach { case (l2top_teemsiInfo, io_teemsiInfo) =>
      l2top_teemsiInfo.fromTile := io_teemsiInfo
    }
    core.module.io.clintTime := l2top.module.io.clintTime.toCore
    l2top.module.io.clintTime.fromTile := io.clintTime
    l2top.module.io.reset_vector.fromTile := io.reset_vector
    l2top.module.io.cpu_halt.fromCore := core.module.io.cpu_halt
    io.cpu_halt := l2top.module.io.cpu_halt.toTile
    l2top.module.io.cpu_critical_error.fromCore := core.module.io.cpu_critical_error
    io.cpu_crtical_error := l2top.module.io.cpu_critical_error.toTile
    l2top.module.io.msiAck.fromCore := core.module.io.msiAck
    io.msiAck := l2top.module.io.msiAck.toTile
    l2top.module.io.teemsiAck zip core.module.io.teemsiAck foreach { case (l2top_teemsiAck, core_teemsiAck) =>
      l2top_teemsiAck.fromCore := core_teemsiAck
    }
    io.teemsiAck zip l2top.module.io.teemsiAck foreach { case (io_teemsiAck, l2top_teemsiAck) =>
      io_teemsiAck := l2top_teemsiAck.toTile
    }

    l2top.module.io.hartIsInReset.resetInFrontend := core.module.io.resetInFrontend
    io.hartIsInReset := l2top.module.io.hartIsInReset.toTile
    l2top.module.io.traceCoreInterface.fromCore <> core.module.io.traceCoreInterface
    io.traceCoreInterface <> l2top.module.io.traceCoreInterface.toTile

    l2top.module.io.beu_errors.icache <> core.module.io.beu_errors.icache
    l2top.module.io.beu_errors.dcache <> core.module.io.beu_errors.dcache
    l2top.module.io.beu_errors.uncache <> core.module.io.beu_errors.uncache

    l2top.module.io.l2_flush_en.foreach { _ := core.module.io.l2_flush_en }
    io.l2_flush_en.foreach { _ := core.module.io.l2_flush_en }
    core.module.io.l2_flush_done := l2top.module.io.l2_flush_done.getOrElse(false.B)
    io.l2_flush_done.foreach { _ := l2top.module.io.l2_flush_done.getOrElse(false.B) }

    l2top.module.io.dft.zip(io.dft).foreach({ case (a, b) => a := b })
    l2top.module.io.dft_reset.zip(io.dft_reset).foreach({ case (a, b) => a := b })
    core.module.io.dft.zip(io.dft).foreach({ case (a, b) => a := b })
    core.module.io.dft_reset.zip(io.dft_reset).foreach({ case (a, b) => a := b })

    if (enableL2) {
      // TODO: add ECC interface of L2
      l2top.module.io.pfCtrlFromCore := core.module.io.l2PfCtrl

      l2top.module.io.beu_errors.l2 <> 0.U.asTypeOf(l2top.module.io.beu_errors.l2)
      core.module.io.l2_hint.bits.sourceId := l2top.module.io.l2_hint.bits.sourceId
      core.module.io.l2_hint.bits.isKeyword := l2top.module.io.l2_hint.bits.isKeyword
      core.module.io.l2_hint.valid := l2top.module.io.l2_hint.valid

      core.module.io.l2PfqBusy := false.B
      core.module.io.debugTopDown.l2MissMatch := l2top.module.io.debugTopDown.l2MissMatch
      l2top.module.io.debugTopDown.robHeadPaddr := core.module.io.debugTopDown.robHeadPaddr
      l2top.module.io.debugTopDown.robTrueCommit := core.module.io.debugTopDown.robTrueCommit
      l2top.module.io.l2_pmp_resp := core.module.io.l2_pmp_resp
      core.module.io.l2_tlb_req <> l2top.module.io.l2_tlb_req
      core.module.io.topDownInfo.l2Miss := l2top.module.io.l2Miss

      core.module.io.perfEvents <> l2top.module.io.perfEvents
    } else {

      l2top.module.io.beu_errors.l2 <> 0.U.asTypeOf(l2top.module.io.beu_errors.l2)
      core.module.io.l2_hint.bits.sourceId := l2top.module.io.l2_hint.bits.sourceId
      core.module.io.l2_hint.bits.isKeyword := l2top.module.io.l2_hint.bits.isKeyword
      core.module.io.l2_hint.valid := l2top.module.io.l2_hint.valid

      core.module.io.l2PfqBusy := false.B
      core.module.io.debugTopDown.l2MissMatch := false.B
      core.module.io.topDownInfo.l2Miss := false.B

      core.module.io.l2_tlb_req.req.valid := false.B
      core.module.io.l2_tlb_req.req.bits := DontCare
      core.module.io.l2_tlb_req.req_kill := DontCare
      core.module.io.l2_tlb_req.resp.ready := true.B

      core.module.io.perfEvents <> DontCare
    }

    io.debugTopDown.robHeadPaddr := core.module.io.debugTopDown.robHeadPaddr
    core.module.io.debugTopDown.l3MissMatch := io.debugTopDown.l3MissMatch
    l2top.module.io.l3Miss.fromTile := io.l3Miss
    core.module.io.topDownInfo.l3Miss := l2top.module.io.l3Miss.toCore

    io.chi.foreach(_ <> l2top.module.io.chi.get)
    io.decoupledCHI.foreach(_ <> l2top.module.io.decoupledCHI.get)
    l2top.module.io.nodeID.foreach(_ := io.nodeID.get)

    if (debugOpts.ResetGen && enableL2) {
      core.module.reset := l2top.module.reset_core
    }

    val acceleratorClock = io.acceleratorClock.getOrElse(clock)
    val acceleratorReset = if (useAcceleratorCDC) {
      withClockAndReset(acceleratorClock, reset) { ResetGen(io.dft_reset) }
    } else {
      reset.asAsyncReset
    }

    // A redirect is a one-cycle pulse in the core clock domain.  Do not wire
    // that pulse directly into the accelerator clock domain: it can be missed
    // or metastable when the external-vector clock is independent.  Encode it
    // as a core-domain toggle and synchronize the toggle into the accelerator
    // domain, where it is decoded back into a one-cycle flush pulse.
    val evuFlushAccelerator = if (useAcceleratorCDC) {
      val flushToggle = RegInit(false.B)
      when (core.module.io.evuFlush.getOrElse(false.B)) {
        flushToggle := ~flushToggle
      }
      val sync0 = withClockAndReset(acceleratorClock, acceleratorReset) {
        RegNext(flushToggle, false.B)
      }
      val sync1 = withClockAndReset(acceleratorClock, acceleratorReset) {
        RegNext(sync0, false.B)
      }
      withClockAndReset(acceleratorClock, acceleratorReset) {
        val seen = RegInit(false.B)
        val pulse = sync1 =/= seen
        when (pulse) { seen := sync1 }
        pulse
      }
    } else {
      core.module.io.evuFlush.getOrElse(false.B)
    }

    evuOpt.foreach { evu =>
      evu.module.clock := acceleratorClock
      evu.module.reset := acceleratorReset
      evuTLAsyncCrossing.foreach { case (source, sink) =>
        source.module.clock := acceleratorClock
        source.module.reset := acceleratorReset
        sink.module.clock := clock
        sink.module.reset := reset
      }
    }

    cuteOpt.foreach { case cute =>
      l2top.module.io.matrixDataOut512L2 := DontCare
      cute.module.io := DontCare
      cute.module.clock := acceleratorClock
      cute.module.reset := acceleratorReset

      cuteTLAsyncCrossings.foreach { case (source, sink) =>
        source.module.clock := acceleratorClock
        source.module.reset := acceleratorReset
        sink.module.clock := clock
        sink.module.reset := reset
      }

      core.module.io.amuCtrl.foreach { amuCtrl =>
        val matrixCommand = Wire(Decoupled(new AmuCtrlIO))
        matrixCommand.valid := amuCtrl.valid && !amuCtrl.bits.evu
        matrixCommand.bits := amuCtrl.bits

        val evuReady = evuOpt.map { evu =>
          val payload = amuCtrl.bits.data.asTypeOf(new EvuCoreCommand(p(ExternalVectorKey)))
          val dispatch = Wire(Decoupled(new EvuDispatchRequest(p(ExternalVectorKey))))
          dispatch.valid := amuCtrl.valid && amuCtrl.bits.evu
          dispatch.bits.instr := payload.instr
          dispatch.bits.base := payload.base
          dispatch.bits.pageMap := payload.pageMap
          dispatch.bits.vl := payload.vl
          dispatch.bits.length := payload.length
          dispatch.bits.aux := payload.aux
          dispatch.bits.pc := payload.pc
          dispatch.bits.config := payload.config
          dispatch.bits.descriptor := payload.descriptor
          dispatch.bits.stride := 0.S
          dispatch.bits.chain := true.B
          if (useAcceleratorCDC) {
            val evuCommandCrossing = Module(new AsyncQueue(
              new EvuDispatchRequest(p(ExternalVectorKey)),
              acceleratorCDC.command
            ))
            evuCommandCrossing.io.enq_clock := clock
            evuCommandCrossing.io.enq_reset := reset.asBool
            evuCommandCrossing.io.deq_clock := acceleratorClock
            evuCommandCrossing.io.deq_reset := acceleratorReset.asBool
            evuCommandCrossing.io.enq <> dispatch
            evu.module.io.dispatch <> evuCommandCrossing.io.deq
          } else {
            evu.module.io.dispatch <> dispatch
          }
          dispatch.ready
        }.getOrElse(false.B)
        amuCtrl.ready := Mux(amuCtrl.bits.evu, evuReady, matrixCommand.ready)

        if (useAcceleratorCDC) {
          val commandCrossing = Module(new AsyncQueue(new AmuCtrlIO, acceleratorCDC.command))
          commandCrossing.io.enq_clock := clock
          commandCrossing.io.enq_reset := reset.asBool
          commandCrossing.io.deq_clock := acceleratorClock
          commandCrossing.io.deq_reset := acceleratorReset.asBool
          commandCrossing.io.enq <> matrixCommand
          val coreCommand = commandCrossing.io.deq.bits
          val cuteCommand = cute.module.io.cute.ctrl2top.amuCtrl.bits
          require(coreCommand.op.getWidth == cuteCommand.op.getWidth)
          require(coreCommand.data.getWidth == cuteCommand.data.getWidth)
          require(coreCommand.pc.isDefined == cuteCommand.pc.isDefined)
          require(coreCommand.coreid.isDefined == cuteCommand.coreid.isDefined)
          cute.module.io.cute.ctrl2top.amuCtrl.valid := commandCrossing.io.deq.valid
          cuteCommand.op := coreCommand.op
          cuteCommand.data := coreCommand.data
          cuteCommand.pc.zip(coreCommand.pc).foreach { case (sink, source) => sink := source }
          cuteCommand.coreid.zip(coreCommand.coreid).foreach { case (sink, source) => sink := source }
          commandCrossing.io.deq.ready := cute.module.io.cute.ctrl2top.amuCtrl.ready
        } else {
          val cuteCommand = cute.module.io.cute.ctrl2top.amuCtrl
          cuteCommand.valid := matrixCommand.valid
          cuteCommand.bits.op := matrixCommand.bits.op
          cuteCommand.bits.data := matrixCommand.bits.data
          cuteCommand.bits.pc.zip(matrixCommand.bits.pc).foreach { case (sink, source) => sink := source }
          cuteCommand.bits.coreid.zip(matrixCommand.bits.coreid).foreach { case (sink, source) => sink := source }
          matrixCommand.ready := cuteCommand.ready
        }
      }
      core.module.io.amuRelease.foreach { amuRelease =>
        if (useAcceleratorCDC) {
          val completionCrossing = Module(new AsyncQueue(new AmuReleaseIO2XS, acceleratorCDC.completion))
          completionCrossing.io.enq_clock := acceleratorClock
          completionCrossing.io.enq_reset := acceleratorReset.asBool
          completionCrossing.io.deq_clock := clock
          completionCrossing.io.deq_reset := reset.asBool
          completionCrossing.io.enq.valid := cute.module.io.cute.ctrl2top.mrelease.valid
          completionCrossing.io.enq.bits := cute.module.io.cute.ctrl2top.mrelease.bits.asTypeOf(
            completionCrossing.io.enq.bits
          )
          cute.module.io.cute.ctrl2top.mrelease.ready := completionCrossing.io.enq.ready
          amuRelease <> completionCrossing.io.deq
        } else {
          amuRelease.bits := cute.module.io.cute.ctrl2top.mrelease.bits.asTypeOf(amuRelease.bits)
          amuRelease.valid := cute.module.io.cute.ctrl2top.mrelease.valid
          cute.module.io.cute.ctrl2top.mrelease.ready := amuRelease.ready
        }
      }

      val matrix_data_out = l2top.module.io.matrixDataOut512L2
      val matrix_data_in = cute.module.io.matrix_data_in
      val outCount = matrix_data_out.length
      val inCount = matrix_data_in.length

      require(outCount > 0, "matrix_data_out must have at least one channel")
      require(inCount > 0, "matrix_data_in must have at least one channel")

      matrix_data_out.foreach(_.ready := false.B)
      val matrixDataToCute = Wire(Vec(inCount, Decoupled(chiselTypeOf(matrix_data_out(0).bits))))
      matrixDataToCute.foreach { in =>
        in.valid := false.B
        in.bits := DontCare
      }

      if (inCount >= outCount) {
        (matrixDataToCute zip matrix_data_out) foreach { case (in, out) =>
          in <> out
        }
      }
      else {
        val baseGroupSize = outCount / inCount
        val largerGroupCount = outCount % inCount

        for (inIdx <- 0 until inCount) {
          // Split consecutive output channels into nearly-even groups.
          // The first `largerGroupCount` groups take one extra channel so the remainder
          // is distributed to the front, e.g. 10 outputs over 3 inputs becomes 4/3/3.
          val groupStart = inIdx * baseGroupSize + (inIdx min largerGroupCount)
          val groupSize = baseGroupSize + (if (inIdx < largerGroupCount) 1 else 0)
          val groupEnd = groupStart + groupSize

          val arb = Module(new Arbiter(chiselTypeOf(matrix_data_out(0).bits), groupSize))
          for ((outIdx, arbIdx) <- (groupStart until groupEnd).zipWithIndex) {
            arb.io.in(arbIdx) <> matrix_data_out(outIdx)
          }
          matrixDataToCute(inIdx) <> arb.io.out
        }
      }

      for (i <- 0 until inCount) {
        if (useAcceleratorCDC) {
          val matrixCrossing = Module(new AsyncQueue(chiselTypeOf(matrixDataToCute(i).bits), acceleratorCDC.matrixData))
          matrixCrossing.io.enq_clock := clock
          matrixCrossing.io.enq_reset := reset.asBool
          matrixCrossing.io.deq_clock := acceleratorClock
          matrixCrossing.io.deq_reset := acceleratorReset.asBool
          matrixCrossing.io.enq <> matrixDataToCute(i)
          matrix_data_in(i) <> matrixCrossing.io.deq
        } else {
          matrix_data_in(i) <> matrixDataToCute(i)
        }
      }

      core.module.io.cutePerf.foreach { corePerf =>
        if (useAcceleratorCDC) {
          val perfCrossing = Module(new CutePerfAsyncCrossing(acceleratorCDC.perfSnapshot))
          perfCrossing.io.sourceClock := acceleratorClock
          perfCrossing.io.sourceReset := acceleratorReset.asBool
          perfCrossing.io.source := cute.module.io.cute.perf
          perfCrossing.io.sinkClock := clock
          perfCrossing.io.sinkReset := reset.asBool
          corePerf := perfCrossing.io.sink
        } else {
          corePerf <> cute.module.io.cute.perf
        }
      }

      cute.module.io.hartId := io.hartId
      cute.module.io.difftestClock.foreach(_ := clock)
      cute.module.io.difftestReset.foreach(_ := reset.asBool)
    }

    evuOpt.foreach { evu =>
      evu.module.io.matrixInput.valid := false.B
      evu.module.io.matrixInput.bits := 0.U.asTypeOf(evu.module.io.matrixInput.bits)
      evu.module.io.matrixOutput.ready := true.B
      evu.module.io.cuteWrite.ready := true.B
    }

    // CUTE emits a completed tile; the bridge slices it into DLEN beats and
    // only asserts the EVU input when the tile has been accepted.
    cuteOpt.foreach { cute =>
      if (evuOpt.isDefined) {
        val evu = evuOpt.get
        val tileBits = cute.module.io.cute.vectorResult.bits.getWidth
        val bridge = withClockAndReset(acceleratorClock, acceleratorReset) {
          Module(new MatrixVectorBridge(tileBits, p(ExternalVectorKey)))
        }
        bridge.io.in.valid := cute.module.io.cute.vectorResult.valid
        bridge.io.in.bits.data := cute.module.io.cute.vectorResult.bits
        bridge.io.in.bits.tileId := cute.module.io.cute.vectorResultTileId
        bridge.io.in.bits.kComplete := cute.module.io.cute.vectorResultKComplete
        bridge.io.in.bits.taskLast := cute.module.io.cute.vectorResultTaskLast
        bridge.io.in.bits.scaleTag := evu.module.io.matrixScaleTag
        bridge.io.in.bits.biasTag := evu.module.io.matrixBiasTag
        cute.module.io.cute.vectorResult.ready := bridge.io.in.ready
        evu.module.io.matrixInput <> bridge.io.out
        evu.module.io.matrixOutput.ready := true.B

        // Quantized EVU output is written directly into the selected CUTE
        // ABMatrixReg entry. Both units are in this tile's vector/matrix
        // clock domain; the Decoupled handshake is the ownership boundary.
        val cuteAbWrite = cute.module.io.cute.evuAbWrite
        cuteAbWrite.valid := evu.module.io.cuteWrite.valid
        cuteAbWrite.bits.matrixReg := evu.module.io.cuteWrite.bits.matrixReg(
          cuteAbWrite.bits.matrixReg.getWidth - 1, 0)
        cuteAbWrite.bits.bank := evu.module.io.cuteWrite.bits.bank(
          cuteAbWrite.bits.bank.getWidth - 1, 0)
        cuteAbWrite.bits.addr := evu.module.io.cuteWrite.bits.entry(
          cuteAbWrite.bits.addr.getWidth - 1, 0)
        if (cuteAbWrite.bits.data.getWidth >= p(ExternalVectorKey).dlenBits) {
          cuteAbWrite.bits.data := Cat(
            0.U((cuteAbWrite.bits.data.getWidth - p(ExternalVectorKey).dlenBits).W),
            evu.module.io.cuteWrite.bits.data)
          cuteAbWrite.bits.byteMask := Cat(
            0.U((cuteAbWrite.bits.byteMask.getWidth - p(ExternalVectorKey).dlenBits / 8).W),
            evu.module.io.cuteWrite.bits.byteMask)
        } else {
          cuteAbWrite.bits.data := evu.module.io.cuteWrite.bits.data(
            cuteAbWrite.bits.data.getWidth - 1, 0)
          cuteAbWrite.bits.byteMask := evu.module.io.cuteWrite.bits.byteMask(
            cuteAbWrite.bits.byteMask.getWidth - 1, 0)
        }
        evu.module.io.cuteWrite.ready := cuteAbWrite.ready
      } else {
        cute.module.io.cute.vectorResult.ready := true.B
      }
    }

    // Retired xv.* commands use dispatch; the raw command port remains local.
    // These commands are non-speculative, so a core branch redirect must not
    // cancel them. Completion/exception reporting still needs a job-state bridge.
    evuOpt.foreach { evu =>
      evu.module.io.command.valid := false.B
      evu.module.io.command.bits := DontCare
      if (env.EnableDifftest) {
        require(!useAcceleratorCDC, "EVU difftest currently requires a common core/accelerator clock")
        val event = difftest.DifftestModule(new difftest.DiffEvuStoreEvent, delay = 3)
        event.coreid := io.hartId
        event.valid := evu.module.io.storeTrace.valid
        event.addr := evu.module.io.storeTrace.bits.addr
        event.data := evu.module.io.storeTrace.bits.data.asTypeOf(event.data)
        event.mask := evu.module.io.storeTrace.bits.mask
        val matrixTrace = evu.module.io.matrixTrace
        val matrixEvent = difftest.DifftestModule(new difftest.DiffEvuMatrixEvent(p(ExternalVectorKey).dlenBits / 64), delay = 3)
        matrixEvent.coreid := io.hartId
        matrixEvent.valid := matrixTrace.valid
        matrixEvent.row := matrixTrace.bits.row
        matrixEvent.col := matrixTrace.bits.col
        matrixEvent.data := matrixTrace.bits.data.asTypeOf(matrixEvent.data)
        matrixEvent.mask := matrixTrace.bits.mask
        matrixEvent.fflags := matrixTrace.bits.fflags
        matrixEvent.last := matrixTrace.bits.last
        val snapshot = evu.module.io.archSnapshot.get
        for (reg <- 0 until p(ExternalVectorKey).vectorRegs) {
          val vrf = difftest.DifftestModule(new difftest.DiffEvuVrfState(p(ExternalVectorKey).vlenBits / 64), delay = 3)
          vrf.coreid := io.hartId
          vrf.index := reg.U
          vrf.valid := snapshot.valid
          vrf.value := snapshot.bits.vr(reg)
        }

      }
      val drain = core.module.io.evuDrain.get
      if (useAcceleratorCDC) {
        val rq = Module(new AsyncQueue(Bool(), acceleratorCDC.command))
        rq.io.enq_clock := clock
        rq.io.enq_reset := reset.asBool
        rq.io.deq_clock := acceleratorClock
        rq.io.deq_reset := acceleratorReset.asBool
        rq.io.enq <> drain.request
        evu.module.io.drain.request <> rq.io.deq
        val rp = Module(new AsyncQueue(new xiangshan.evu.EvuDrainResult, acceleratorCDC.completion))
        rp.io.enq_clock := acceleratorClock
        rp.io.enq_reset := acceleratorReset.asBool
        rp.io.deq_clock := clock
        rp.io.deq_reset := reset.asBool
        rp.io.enq <> evu.module.io.drain.response
        drain.response <> rp.io.deq
      } else {
        evu.module.io.drain <> drain
      }
      core.module.io.evuCompletion.foreach { completion =>
        if (useAcceleratorCDC) {
          val evuCompletionCrossing = Module(new AsyncQueue(
            new EvuCompletion(p(ExternalVectorKey)),
            acceleratorCDC.completion
          ))
          evuCompletionCrossing.io.enq_clock := acceleratorClock
          evuCompletionCrossing.io.enq_reset := acceleratorReset.asBool
          evuCompletionCrossing.io.deq_clock := clock
          evuCompletionCrossing.io.deq_reset := reset.asBool
          evuCompletionCrossing.io.enq <> evu.module.io.completion
          completion <> evuCompletionCrossing.io.deq
        } else {
          completion <> evu.module.io.completion
        }
      }
      // xv commands reach the EVU only after architectural retirement. A core
      // redirect therefore cannot cancel them; reset or an explicit future job
      // cancellation command owns accelerator-domain flushing.
      evu.module.io.flush := evuFlushAccelerator
      // With AME selected there is no CUTE consumer.  Retired AMU entries
      // are routed directly to the EVU dispatch adapter.
      if (cuteOpt.isEmpty) {
        core.module.io.amuCtrl.foreach { amuCtrl =>
          val payload = amuCtrl.bits.data.asTypeOf(new EvuCoreCommand(p(ExternalVectorKey)))
          evu.module.io.dispatch.valid := amuCtrl.valid && amuCtrl.bits.evu
          evu.module.io.dispatch.bits.instr := payload.instr
          evu.module.io.dispatch.bits.base := payload.base
          evu.module.io.dispatch.bits.pageMap := payload.pageMap
          evu.module.io.dispatch.bits.vl := payload.vl
          evu.module.io.dispatch.bits.length := payload.length
          evu.module.io.dispatch.bits.aux := payload.aux
          evu.module.io.dispatch.bits.pc := payload.pc
          evu.module.io.dispatch.bits.config := payload.config
          evu.module.io.dispatch.bits.descriptor := payload.descriptor
          evu.module.io.dispatch.bits.stride := 0.S
          evu.module.io.dispatch.bits.chain := true.B
          amuCtrl.ready := Mux(amuCtrl.bits.evu, evu.module.io.dispatch.ready, false.B)
        }
      }
    }

    if (HasMatrixExtension && cuteOpt.isEmpty) {
      core.module.io.cutePerf.foreach(_ := 0.U.asTypeOf(new CutePerfToCoreIO))
    }

    if (!HasMatrixExtension) {
      l2top.module.io.matrixDataOut512L2 := DontCare
      l2top.module.io.matrixDataOut512L2.foreach(_.ready := false.B)
    }
  }

  lazy val module = new XSTileImp(this)
}
