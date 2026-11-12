package evu

import circt.stage.ChiselStage
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.EvuParams

/** Run separately on the build server: CHIRRTL-only checks do not detect the
  * cross-module busy -> FENCE -> completion-ready -> command-credit loop.
  */
class EvuCompletionLoopSpec extends AnyFlatSpec with Matchers {
  behavior of "EVU completion credit and FENCE wiring"

  it should "pass firtool cycle checking with the production-width vector and memory paths" in {
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    val ep = EvuParams(enable = true)
    // Emit to an in-memory string. This invokes firtool, but does not build or
    // run a Verilator emulator. Keep the real control/backend/memory wiring.
    val rtl = ChiselStage.emitSystemVerilog(LazyModule(new EvuTopHarness(ep)).module,
      firtoolOpts = Array("--disable-annotation-unknown"))
    rtl should include ("module EvuTop")
  }
}
