package xiangshan.evucheck

import circt.stage.ChiselStage
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import top.ArgParser
import xiangshan.{XSCoreParamsKey, XSTileKey}
import xiangshan.backend.exu.ExeUnit

class EvuExeUnitElaborationSpec extends AnyFlatSpec with Matchers {
  behavior of "EVU execution unit input wiring"

  private def parameters(enableEvu: Boolean): Parameters = {
    val config = if (enableEvu) "DefaultExternalVectorMatrixConfig" else "DefaultMatrixConfig"
    val (base, _, _) = ArgParser.parse(Array("--config", config,
      "--disable-perf", "--disable-alwaysdb"))
    val core = base(XSTileKey).head
    val p = base.alterPartial { case XSCoreParamsKey => core }
    val backend = p(XSCoreParamsKey).backendParams
    backend.allSchdParams.foreach(_.bindBackendParam(backend))
    backend.allIssueParams.foreach(_.bindBackendParam(backend))
    backend.allExuParams.zipWithIndex.foreach { case (exu, index) =>
      exu.bindBackendParam(backend)
      exu.updateExuIdx(index)
    }
    p
  }

  for (enableEvu <- Seq(false, true)) {
    it should s"elaborate the production MRELEASE unit with EVU enabled=$enableEvu" in {
      implicit val p: Parameters = parameters(enableEvu)
      val exu = p(XSCoreParamsKey).backendParams.allExuParams.find(_.name == "MRELEASE").get
      val circuit = ChiselStage.emitCHIRRTL(LazyModule(new ExeUnit(exu)).module)
      circuit should include ("module ExeUnit")
    }
  }

  it should "retain the PC offset input for branch and jump functions" in {
    implicit val p: Parameters = parameters(enableEvu = true)
    val exu = p(XSCoreParamsKey).backendParams.allExuParams.find(_.name == "BJU0").get
    val circuit = ChiselStage.emitCHIRRTL(LazyModule(new ExeUnit(exu)).module)
    circuit should include ("nextPcOffset")
  }
}
