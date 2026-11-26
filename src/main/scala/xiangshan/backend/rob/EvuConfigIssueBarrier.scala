package xiangshan.backend.rob

import chisel3._
import chisel3.util.{RegEnable, Valid}

object EvuConfigIssueBarrier {
  def holds(owner: Valid[RobPtr], candidate: RobPtr, evu: Bool): Bool =
    owner.valid && evu && candidate > owner.bits
}

/** Only the matching publication or a squash of this owner releases its consumers. */
class EvuConfigIssueBarrier(entries: Int) extends Module {
  val io = IO(new Bundle {
    val admit = Input(Valid(new RobPtr(entries)))
    val published = Input(Valid(UInt((new RobPtr(entries)).getWidth.W)))
    val redirect = Input(Valid(new RobPtr(entries)))
    val flushSelf = Input(Bool())
    val owner = Output(Valid(new RobPtr(entries)))
  })
  val pending = RegInit(false.B)
  val tag = RegEnable(io.admit.bits, io.admit.valid && !io.redirect.valid)
  val squash = io.redirect.valid && (tag > io.redirect.bits || (io.flushSelf && tag === io.redirect.bits))
  when(io.admit.valid && !io.redirect.valid) {
    assert(!pending, "Cannot replace an unpublished EVU configuration")
    pending := true.B
  }
  when(pending && (squash || (io.published.valid && io.published.bits === tag.asUInt))) {
    pending := false.B
  }
  io.owner.valid := pending
  io.owner.bits := tag
}
