package xiangshan.evucheck

import circt.stage.ChiselStage
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import top.{ArgParser, WithExternalVector, XSTop}

/** Exercise the real core/DTLB/ROB/EVU wiring without generating Verilog. */
object EvuMmuElaborationCheck {
  def main(args: Array[String]): Unit = {
    val (base, _, _) = ArgParser.parse(Array("--config", "MinimalMatrixConfig",
      "--disable-perf", "--disable-alwaysdb"))
    implicit val p: Parameters = new WithExternalVector() ++ base
    val circuit = ChiselStage.emitCHIRRTL(LazyModule(new XSTop).module)
    require(circuit.contains("EvuAddressCheck"), "Core preflight was not instantiated")
    require(circuit.contains("EvuPageTranslate"), "EVU physical-page lookup was not instantiated")
    println(s"EVU MMU full-core CHIRRTL check passed (${circuit.length} characters)")
  }
}
