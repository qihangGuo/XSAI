package evu

import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu._

/** Structural checks only: CHIRRTL stays in memory; no firtool, Verilog or simulator. */
object EvuHbl2ElaborationCheck {
  def main(args: Array[String]): Unit = {
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    val ep = EvuParams(enable = true, vlenBits = 512)
    val target = args.headOption.getOrElse("adapter")
    val chirrtl = target match {
      case "adapter" => circt.stage.ChiselStage.emitCHIRRTL(new EvuHbl2LineAdapter(ep))
      case "top" => circt.stage.ChiselStage.emitCHIRRTL(LazyModule(new EvuTopHarness(ep, 32)).module)
      case "architecture" => circt.stage.ChiselStage.emitCHIRRTL(
        LazyModule(new EvuTopHarness(ep.copy(enableDifftest = true), 32)).module)
      case other => throw new IllegalArgumentException(s"Unknown EVU check: $other")
    }
    require(chirrtl.contains("circuit"), s"Empty CHIRRTL for $target")
    println(s"EVU $target CHIRRTL check passed (${chirrtl.length} characters; no Verilog emitted)")
  }
}
