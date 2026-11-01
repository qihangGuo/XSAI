package evu

import chisel3._
import circt.stage.ChiselStage
import freechips.rocketchip.diplomacy.{DisableMonitors, LazyModule, LazyModuleImp}
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.EvuParams
import xiangshan.evu.area.{EvuAreaMain, EvuAreaTop}

class EvuAreaTopSpec extends AnyFlatSpec with Matchers {
  behavior of "Standalone EVU area boundary"
  it should "preserve both functional boundaries and production SRAM in memory-only CHIRRTL" in {
    implicit val p: Parameters = EvuAreaMain.parameters
    // SRAM definitions are cached globally: both configurations share one circuit.
    val pair = DisableMonitors { p => LazyModule(new LazyModule()(p) {
      val bare = LazyModule(new EvuAreaTop(EvuParams(enable = true))(p))
      val bridged = LazyModule(new EvuAreaTop(EvuParams(enable = true), withBridge = true)(p))
      lazy val module = new LazyModuleImp(this) {
        val withoutBridge = IO(chiselTypeOf(bare.module.io))
        val withBridge = IO(chiselTypeOf(bridged.module.io))
        withoutBridge <> bare.module.io
        withBridge <> bridged.module.io
      }
    }) }
    val ir = ChiselStage.emitCHIRRTL(pair.module)
    ir should include ("module EvuAreaTop")
    ir should include ("withoutBridge")
    ir should include ("withBridge")
    ir should include ("matrixInput")
    ir should include ("tileInput")
    ir should include ("cuteWrite")
    ir should include ("drain")
    ir should include ("sram_array_1p64x64m8")
    ir should include ("module MatrixVectorBridge")
    ir should not include "debugShadow"
    ir should not include "archSnapshot"
    ir should not include "module XSTop"
  }
}
