package xiangshan.backend.rob

import chisel3._
import chisel3.util._

/** Observes the registered barrier without changing its release condition. */
object SetvliBarrierEvents {
  case class Events(nonempty: Bool, drainOnly: Bool, release: Bool)
  def apply(enqueued: Bool, isSetvli: Bool, blocked: Bool, dispatchDemand: Bool,
      robEmpty: Bool, evuBusy: Bool): Events = {
    val ownerIsSetvli = RegEnable(isSetvli, false.B, enqueued)
    val stalled = blocked && ownerIsSetvli && dispatchDemand
    Events(stalled && !robEmpty, stalled && robEmpty && evuBusy, stalled && robEmpty && !evuBusy)
  }
}

/** The ordinary-memory exception and a published SETVLI only relax enqueue. */
class RobEnqueueBarrier(tagWidth: Int) extends Module {
  val io = IO(new Bundle {
    val enqueue = Flipped(Valid(Bool())) // true only for ordinary EVU memory
    val setvliOwner = Flipped(Valid(UInt(tagWidth.W))) // sampled with enqueue
    val configPublished = Flipped(Valid(UInt(tagWidth.W)))
    val cancel = Input(Bool())
    val releasedWhileBusy = Output(Bool())
    val robEmpty = Input(Bool())
    val evuBusy = Input(Bool())
    val blocked = Output(Bool())
  })
  private val blocked = RegInit(false.B)
  private val needsEvuDrain = RegInit(true.B)
  private val owner = RegEnable(io.setvliOwner.bits, io.enqueue.valid && io.setvliOwner.valid)
  private val isSetvli = RegInit(false.B)
  private val published = RegInit(false.B)
  private val matches = io.configPublished.valid && io.configPublished.bits === owner
  private val configReady = isSetvli && !io.cancel && (published || matches)
  io.releasedWhileBusy := blocked && io.robEmpty && io.evuBusy && configReady
  when(io.robEmpty && (!needsEvuDrain || !io.evuBusy || configReady)) {
    blocked := false.B
  }
  when(blocked && isSetvli && matches) { published := true.B }
  when(io.enqueue.valid) {
    blocked := true.B
    needsEvuDrain := !io.enqueue.bits
    isSetvli := io.setvliOwner.valid
    published := false.B
  }
  // A squash revokes only the shortcut; the original drain path still works.
  when(io.cancel) { isSetvli := false.B }
  io.blocked := blocked
}
