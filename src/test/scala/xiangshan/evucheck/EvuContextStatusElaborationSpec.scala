package xiangshan.evucheck

import circt.stage.ChiselStage
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import top.ArgParser
import xiangshan.{ExternalVectorKey, XSCoreParamsKey, XSTileKey}
import xiangshan.backend.fu.NewCSR.{CSRModule, EvuCSR, MstatusModule, SstatusBundle}

class EvuContextStatusElaborationSpec extends AnyFlatSpec with Matchers {
  behavior of "EVU context status CSR read filters"

  private def parameters(enableEvu: Boolean): Parameters = {
    val name = if (enableEvu) "DefaultExternalVectorMatrixConfig" else "DefaultMatrixConfig"
    val (base, _, _) = ArgParser.parse(Array("--config", name,
      "--disable-perf", "--disable-alwaysdb"))
    base.alterPartial { case XSCoreParamsKey => base(XSTileKey).head }
  }

  for (enableEvu <- Seq(false, true)) {
    it should s"elaborate mstatus and its sstatus alias with EVU enabled=$enableEvu" in {
      implicit val p: Parameters = parameters(enableEvu)
      // In-memory CHIRRTL exercises the read filters without invoking firtool
      // or generating Verilog. Scala compilation alone cannot check enum identity.
      val circuit = ChiselStage.emitCHIRRTL(new MstatusModule)
      circuit should include ("module MStatusModule")
    }

    it should s"elaborate the vsstatus read filter with EVU enabled=$enableEvu" in {
      implicit val p: Parameters = parameters(enableEvu)
      // vsstatus uses SstatusBundle through CSRModule, unlike the mstatus alias.
      val circuit = ChiselStage.emitCHIRRTL(new CSRModule("VSstatus", new SstatusBundle))
      circuit should include ("module VSstatusModule")
    }
  }

  it should "elaborate every EVU CSR read and snapshot word" in {
    val p = parameters(enableEvu = true)
    // Covers Bool-valued vxsat and configured as well as the other CSR words.
    val circuit = ChiselStage.emitCHIRRTL(new EvuCSR(p(ExternalVectorKey)))
    circuit should include ("module EvuCSR")
  }
}
