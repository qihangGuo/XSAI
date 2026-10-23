package xiangshan.evucheck

import circt.stage.ChiselStage
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import top.ArgParser
import xiangshan.{XSCoreParamsKey, XSTileKey}
import xiangshan.cache.mmu.PTWNewFilter

class EvuPtwFilterSpec extends AnyFlatSpec with Matchers {
  behavior of "EVU shared page-table walker"

  private def parameters(enableEvu: Boolean): Parameters = {
    val name = if (enableEvu) "DefaultExternalVectorMatrixConfig" else "DefaultMatrixConfig"
    val (base, _, _) = ArgParser.parse(Array("--config", name,
      "--disable-perf", "--disable-alwaysdb"))
    base.alterPartial { case XSCoreParamsKey => base(XSTileKey).head }
  }

  for (enableEvu <- Seq(false, true)) {
    it should s"elaborate the production PTW filter with EVU enabled=$enableEvu" in {
      implicit val p: Parameters = parameters(enableEvu)
      val core = p(XSCoreParamsKey)
      val width = core.backendParams.LdExuCnt + 1 + core.backendParams.StaCnt + 2 +
        (if (enableEvu) 1 else 0)
      val circuit = ChiselStage.emitCHIRRTL(new PTWNewFilter(width,
        core.l2tlbParameters.dfilterSize, core.ldtlbParameters.fenceDelay))
      circuit should include ("module PTWNewFilter")
    }
  }
}
