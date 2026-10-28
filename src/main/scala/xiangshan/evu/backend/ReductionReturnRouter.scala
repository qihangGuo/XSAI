package xiangshan.evu.backend

import chisel3._
import chisel3.util._

class ReductionReturn(vatBits: Int, egBits: Int, dataBits: Int) extends Bundle {
  val vat = UInt(vatBits.W)
  val slot = UInt(2.W)
  val eg = UInt(egBits.W)
  val data = UInt(dataBits.W)
  val mask = UInt(dataBits.W)
}

/** Each instruction uses one FU; distinct contexts accept simultaneous returns. */
class ReductionReturnRouter(vatBits: Int, egBits: Int, dataBits: Int, contexts: Int, producers: Int) extends Module {
  val io = IO(new Bundle {
    val owners = Input(Vec(contexts, Valid(UInt(vatBits.W))))
    val returns = Input(Vec(producers, Valid(new ReductionReturn(vatBits, egBits, dataBits))))
    val routed = Output(Vec(contexts, Valid(new ReductionReturn(vatBits, egBits, dataBits))))
  })
  for ((out, c) <- io.routed.zipWithIndex) {
    val matches = io.returns.map(r => r.valid && io.owners(c).valid && r.bits.vat === io.owners(c).bits)
    assert(PopCount(matches) <= 1.U, "Two FUs wrote the same reduction context")
    out.valid := matches.reduce(_ || _)
    out.bits := Mux1H(matches, io.returns.map(_.bits))
  }
  for (r <- io.returns) {
    when(r.valid) {
      assert(PopCount(io.owners.map(o => o.valid && o.bits === r.bits.vat)) === 1.U,
        "Reduction return has no unique active VAT owner")
    }
  }
}
