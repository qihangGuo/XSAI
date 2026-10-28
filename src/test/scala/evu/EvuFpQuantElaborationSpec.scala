package evu

import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

/** In-memory CHIRRTL only: no firtool, simulator, or Verilog output. */
class EvuFpQuantElaborationSpec extends AnyFlatSpec with Matchers {
  behavior of "FP32 quantization and the retained special sequencer"
  for (vlen <- Seq(512, 4096)) {
    it should s"elaborate the packed write path with VLEN=$vlen" in {
      val ir = ChiselStage.emitCHIRRTL(new EvuSpecialSequencer(EvuParams(enable = true, vlenBits = vlen)))
      ir should include ("module EvuFpQuant")
      ir should include ("module EvuSpecialSequencer")
    }
  }
  it should "retain elaboration of the small configuration without packed FP capability" in {
    val ep = EvuParams(enable = true, vlenBits = 128, dlenBits = 64, mlenBits = 64, lanes = 1)
    ChiselStage.emitCHIRRTL(new EvuSpecialSequencer(ep)) should include ("module EvuSpecialSequencer")
  }
}
