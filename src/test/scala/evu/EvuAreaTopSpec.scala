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
    val ep = EvuParams(enable = true)
    ep.dlenBits shouldBe 512
    ep.mlenBits shouldBe 512
    ep.lanes shouldBe 8
    ep.vrfBanks shouldBe 4
    ep.memoryCommandDepth shouldBe 4
    ep.vectorRegs * ep.vlenBits shouldBe 16 * 1024 * 8
    ep.lanes * ep.vrfBanks shouldBe 32
    ep.vectorRegs * ep.vlenBits / ep.dlenBits / ep.vrfBanks shouldBe 64
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
    ir should include ("inst storage_7_3 of")
    ir should not include "inst storage_0_4 of"
    ir should include ("module MatrixVectorBridge")
    ir should not include "debugShadow"
    ir should not include "archSnapshot"
    val diagnosticRegisters = "\\breg(?:reset)?\\s+(?:fp_(?:fma|cmp|exp)_(?:idleWithWork|readyArbitrationWait|queueIdle|resultSlotWait|priorityIssues|noWork|loadWait|forwardWait)|vectorForwards|vectorForwardNewer|demandPrefetchWait|prefetchDeferred|prefetchUsed|prefetchDiscarded)\\b".r
    diagnosticRegisters.findFirstIn(ir) shouldBe None
    ir should not include "vmuRmwGets"
    ir should not include "vmuRmwMerges"
    ir should not include "module XSTop"
  }
}
