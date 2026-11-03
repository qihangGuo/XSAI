package xiangshan.evu

import chisel3._
import chisel3.util._

/** Ordered line checks share the existing one-request-per-cycle TLB and PMP.
  * Only metadata is buffered. A miss replays its own slot; younger answers
  * wait behind it so the range checker still reports the earliest fault.
  */
class EvuPreflightPipeline(entries: Int = 8) extends Module {
  require(entries >= 4 && isPow2(entries))
  private val tagBits = log2Ceil(entries)
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new EvuAddressLookupRequest))
    val response = Decoupled(new EvuAddressResponse)
    val cancel = Input(Bool())
    val translation = new Bundle {
      val request = Decoupled(new Bundle {
        val check = new EvuAddressLookupRequest
        val tag = UInt(tagBits.W)
      })
      val response = Flipped(Valid(new Bundle {
        val tag = UInt(tagBits.W)
        val answer = new EvuAddressResponse
        val miss = Bool()
        val uncacheable = Bool()
      }))
    }
    // PMP is checked in the cycle after the nonblocking TLB response.
    val permissionFault = Input(Bool())
    val busy = Output(Bool())
  })

  val empty :: ready :: translating :: permission :: complete :: Nil = Enum(5)
  val states = RegInit(VecInit.fill(entries)(empty))
  val requests = Reg(Vec(entries, new EvuAddressLookupRequest))
  val answers = Reg(Vec(entries, new EvuAddressResponse))
  val head = RegInit(0.U(tagBits.W))
  val tail = RegInit(0.U(tagBits.W))
  // The TLB returns after one cycle and PMP after one further cycle. Drain
  // both stages on cancel before a new range may reuse their slot IDs.
  val cooling = RegInit(0.U(2.W))
  when(cooling =/= 0.U) { cooling := cooling - 1.U }
  val active = !io.cancel && cooling === 0.U

  io.request.ready := active && states(tail) === empty
  when(io.request.fire) {
    requests(tail) := io.request.bits
    states(tail) := ready
    tail := tail + 1.U
  }
  val candidates = VecInit((0 until entries).map { offset =>
    val slot = (head + offset.U)(tagBits - 1, 0)
    states(slot) === ready
  })
  val selected = (head + PriorityEncoder(candidates))(tagBits - 1, 0)
  io.translation.request.valid := active && candidates.asUInt.orR
  io.translation.request.bits.check := requests(selected)
  io.translation.request.bits.tag := selected
  when(io.translation.request.fire) { states(selected) := translating }

  val translated = io.translation.response
  val checking = RegNext(active && translated.valid && !translated.bits.miss, false.B)
  val permissionTag = RegEnable(translated.bits.tag, active && translated.valid)
  val permissionAnswer = RegEnable(translated.bits.answer, active && translated.valid)
  val uncacheable = RegEnable(translated.bits.uncacheable, active && translated.valid)
  when(active && translated.valid) {
    assert(states(translated.bits.tag) === translating, "EVU preflight response has no owner")
    states(translated.bits.tag) := Mux(translated.bits.miss, ready, permission)
  }
  when(active && checking) {
    assert(states(permissionTag) === permission, "EVU preflight PMP result has no owner")
    answers(permissionTag) := permissionAnswer
    when(permissionAnswer.fault === EvuAddressFault.None && (io.permissionFault || uncacheable)) {
      answers(permissionTag).fault := EvuAddressFault.Access
    }
    states(permissionTag) := complete
  }
  io.response.valid := active && states(head) === complete
  io.response.bits := answers(head)
  when(io.response.fire) {
    states(head) := empty
    head := head + 1.U
  }
  when(io.cancel) {
    states.foreach(_ := empty)
    head := 0.U
    tail := 0.U
    checking := false.B
    cooling := 3.U
  }
  io.busy := states.map(_ =/= empty).reduce(_ || _) || cooling =/= 0.U
}
