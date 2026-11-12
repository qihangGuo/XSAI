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

  private def parameters(enableEvu: Boolean, enableDifftest: Boolean = false): Parameters = {
    val config = if (enableEvu) "DefaultExternalVectorMatrixConfig" else "DefaultMatrixConfig"
    val (base, _, _) = ArgParser.parse(Array("--config", config,
      "--disable-perf", "--disable-alwaysdb") ++
      (if (enableDifftest) Array("--enable-difftest") else Array.empty[String]))
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

    it should s"elaborate the shared BJU3 diagnostics with EVU enabled=$enableEvu" in {
      implicit val p: Parameters = parameters(enableEvu, enableDifftest = true)
      val exu = p(XSCoreParamsKey).backendParams.allExuParams.find(_.name == "BJU3").get
      val circuit = ChiselStage.emitCHIRRTL(LazyModule(new ExeUnit(exu)).module)
      circuit should include ("module ExeUnit")
      if (enableEvu) circuit should include ("[EVU-BJU3]")
      else circuit should not include ("[EVU-BJU3]")
    }
  }

  it should "retain the PC offset input for branch and jump functions" in {
    implicit val p: Parameters = parameters(enableEvu = true)
    val exu = p(XSCoreParamsKey).backendParams.allExuParams.find(_.name == "BJU0").get
    val circuit = ChiselStage.emitCHIRRTL(LazyModule(new ExeUnit(exu)).module)
    circuit should include ("nextPcOffset")
  }
}
