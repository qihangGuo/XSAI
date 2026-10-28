package xiangshan.evucheck

import circt.stage.ChiselStage
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import top.ArgParser
import xiangshan.{XSCoreParamsKey, XSTileKey}
import xiangshan.backend.CtrlBlock

class EvuCtrlBlockPcSpec extends AnyFlatSpec with Matchers {
  behavior of "CtrlBlock PC read ports"

  for (enableEvu <- Seq(false, true)) {
    it should s"initialize every PC response through firtool with EVU enabled=$enableEvu" in {
      val config = if (enableEvu) "DefaultExternalVectorMatrixConfig" else "DefaultMatrixConfig"
      val (base, _, _) = ArgParser.parse(Array("--config", config,
        "--disable-perf", "--disable-alwaysdb", "--disable-always-basic-diff"))
      val core = base(XSTileKey).head.copy(RobSize = 32)
      implicit val p: Parameters = base.alterPartial { case XSCoreParamsKey => core }
      val backend = core.backendParams
      backend.configChecks
      backend.memSchdParams.get.issueBlockParams.filter(_.StdCnt == 0).zipWithIndex.foreach {
        case (iq, index) => iq.updateIdx(index)
      }
      backend.allSchdParams.foreach(_.bindBackendParam(backend))
      backend.allIssueParams.foreach(_.bindBackendParam(backend))
      backend.allExuParams.zipWithIndex.foreach { case (exu, index) =>
        exu.bindBackendParam(backend)
        exu.updateIQWakeUpConfigs(backend.iqWakeUpParams)
        exu.updateExuIdx(index)
      }
      backend.updateCopyPdestInfo
      backend.allExuParams.foreach(_.copyNum)
      // CHIRRTL elaboration alone accepts unconnected outputs. Run firtool's
      // FIRRTL pipeline, which checks initialization without emitting Verilog.
      val circuit = ChiselStage.emitFIRRTLDialect(LazyModule(new CtrlBlock(backend)).module,
        firtoolOpts = Array("--disable-annotation-unknown"))
      circuit should include ("firrtl.module @CtrlBlock")
    }
  }
}
