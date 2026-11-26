package xiangshan.evucheck

import circt.stage.ChiselStage
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import top.ArgParser
import xiangshan.{XSCoreParamsKey, XSTileKey}
import xiangshan.backend.dispatch.NewDispatch
import xiangshan.backend.fu.FuType
import xiangshan.backend.issue.IssueQueue

class EvuDispatchElaborationSpec extends AnyFlatSpec with Matchers {
  it should "lower production dispatch and its EVU config-gated issue queue" in {
    val (base, _, _) = ArgParser.parse(Array("--config", "DefaultExternalVectorMatrixConfig",
      "--enable-difftest", "--disable-perf", "--disable-alwaysdb"))
    val core = base(XSTileKey).head
    implicit val p: Parameters = base.alterPartial { case XSCoreParamsKey => core }
    val backend = core.backendParams
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
    val rtl = ChiselStage.emitSystemVerilog(new NewDispatch,
      firtoolOpts = Array("-O=release", "--disable-annotation-unknown"))
    rtl should include ("module NewDispatch(")
    val evuQueues = backend.intSchdParams.get.issueBlockParams.filter(_.getFuCfgs.exists(_.fuType == FuType.evu))
    evuQueues should not be empty
    for (iq <- evuQueues) {
      val issueRtl = ChiselStage.emitSystemVerilog(LazyModule(new IssueQueue(iq)).module,
        firtoolOpts = Array("-O=release", "--disable-annotation-unknown"))
      issueRtl should include ("evuConfigOwner_valid")
    }
  }
}
