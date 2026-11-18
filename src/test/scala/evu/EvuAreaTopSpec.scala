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
  it should "retain production SRAM and exclude all matrix coupling in pure-vector CHIRRTL" in {
    val ep = EvuParams(enable = true)
    ep.dlenBits shouldBe 512
    ep.mlenBits shouldBe 512
    ep.lanes shouldBe 8
    ep.vrfBanks shouldBe 4
    ep.memoryCommandDepth shouldBe 4
    ep.fmaOrdinaryContexts shouldBe 2
    ep.parallelFp32Div shouldBe true
    ep.vectorRegs * ep.vlenBits shouldBe 16 * 1024 * 8
    ep.lanes * ep.vrfBanks shouldBe 32
    ep.vectorRegs * ep.vlenBits / ep.dlenBits / ep.vrfBanks shouldBe 64
    implicit val p: Parameters = EvuAreaMain.parameters
    val top = DisableMonitors { p => LazyModule(new EvuAreaTop(ep)(p)) }
    val ir = ChiselStage.emitCHIRRTL(top.module)
    ir should include ("module EvuAreaTop")
    ir should not include "matrixInput"
    ir should not include "tileInput"
    ir should not include "cuteWrite"
    ir should include ("drain")
    ir should include ("sram_array_1p64x64m8")
    ir should include ("inst storage_7_3 of")
    ir should not include "inst storage_0_4 of"
    ir should not include "module MatrixVectorBridge"
    ir should not include "module EvuFpQuant"
    ir should not include "module EvuMatrixDequant"
    ir should not include "module EvuMatrixStoreBuffer"
    ir should not include "module EvuSpecialSequencer"
    ir should not include "debugShadow"
    ir should not include "archSnapshot"
    val diagnosticRegisters = "\\breg(?:reset)?\\s+(?:fp_(?:fma|cmp|exp)_(?:idleWithWork|readyArbitrationWait|queueIdle|resultSlotWait|priorityIssues|noWork|loadWait|forwardWait)|vectorForwards|vectorForwardNewer|demandPrefetchWait|prefetchDeferred|prefetchUsed|prefetchDiscarded)\\b".r
    diagnosticRegisters.findFirstIn(ir) shouldBe None
    "\\breg(?:reset)?\\s+diag_\\w+".r.findFirstIn(ir) shouldBe None
    ir should include ("vxsfp_fma_3")
    ir should not include "vxsfp_fma_4"
    ir should not include "vmuRmwGets"
    ir should not include "vmuRmwMerges"
    ir should not include "module XSTop"
  }

  it should "check production forwarding and collector wiring without emitting Verilog" in {
    implicit val p: Parameters = EvuAreaMain.parameters
    val top = DisableMonitors { p => LazyModule(new EvuAreaTop(EvuParams(enable = true))(p)) }
    // HW dialect lowering runs firtool's combinational-cycle check, unlike emitCHIRRTL.
    val ir = ChiselStage.emitHWDialect(top.module,
      firtoolOpts = Array("-O=release", "--disable-annotation-unknown", "--disable-all-randomization"))
    ir should include ("hw.module @EvuAreaTop")
  }
}
