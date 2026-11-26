package xiangshan.evu.backend

import chisel3._
import chisel3.util._
import xiangshan.evu.common._

object ReductionFirstGroup {
  def allNormal(data: UInt): Bool = {
    require(data.getWidth == 512)
    data.asTypeOf(Vec(16, UInt(32.W))).map { lane =>
      val exp = lane(30, 23)
      exp.orR && !exp.andR
    }.reduce(_ && _)
  }

  def route(input: DecoupledIO[ExecuteMicroOpWithData], fu: DecoupledIO[ExecuteMicroOpWithData],
      owners: Seq[SpecialSequencer]): Bool = {
    val op = input.bits
    val firstVisit = op.acc_slot =/= 0.U && op.eidx === Cat(op.acc_slot, 0.U(4.W))
    val owner = owners.map(s => s.io.busy && s.io.vat === op.vat && !s.io.acc_fu_resp.valid)
    val direct = op.acc && op.opff6 === OPFFunct6.fredusum && !op.acc_fold && !op.acc_merge &&
      op.vm && op.sew === 2.U && op.rvs2_eew === 2.U && op.vd_eew === 2.U &&
      op.eidx_mask.andR && firstVisit && allNormal(op.rvs2_data) && owner.reduce(_ || _)
    // Preserve the selected sequencer's normal handshake and leave the existing
    // writeback reservations conservative. No FU input or flag event on a load.
    input.ready := fu.ready
    fu.bits := op
    fu.valid := input.valid && !direct
    val fire = input.fire && direct
    owners.zip(owner).foreach { case (s, hit) =>
      s.io.acc_load.get.valid := fire && hit
      s.io.acc_load.get.bits := op.rvs2_data
    }
    fire
  }
}
