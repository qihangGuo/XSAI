package xiangshan.backend.rob

import chisel3._
import chisel3.util._

/** Reserve by program order at dispatch. Address operands may arrive in either order. */
class EvuLoadQueue[T <: Data](gen: T, tagWidth: Int) extends Module {
  val io = IO(new Bundle {
    val reserve = Flipped(Valid(UInt(tagWidth.W)))
    val capture = Flipped(Decoupled(new Bundle {
      val tag = UInt(tagWidth.W)
      val data = gen.cloneType
    }))
    val head = Input(UInt(tagWidth.W))
    val retire = Decoupled(gen.cloneType)
    val tags = Output(Vec(2, UInt(tagWidth.W)))
    val kill = Input(Vec(2, Bool()))
    val full = Output(Bool())
    val pending = Output(Bool())
  })
  private val reserved = RegInit(VecInit.fill(2)(false.B))
  private val captured = RegInit(VecInit.fill(2)(false.B))
  private val tags = Reg(Vec(2, UInt(tagWidth.W)))
  private val data = Reg(Vec(2, gen.cloneType))
  private val firstFree = PriorityEncoderOH(~reserved.asUInt)
  private val matches = tags.indices.map(i => reserved(i) && !captured(i) && !io.kill(i) && tags(i) === io.capture.bits.tag)
  private val atHead = tags.indices.map(i => captured(i) && !io.kill(i) && tags(i) === io.head)
  io.tags := tags
  io.full := reserved.asUInt.andR
  io.pending := reserved.asUInt.orR
  io.capture.ready := VecInit(matches).asUInt.orR
  io.retire.valid := VecInit(atHead).asUInt.orR
  io.retire.bits := Mux1H(atHead, data)
  when(io.reserve.valid) {
    assert(!io.full && !(0 until 2).map(i => reserved(i) && tags(i) === io.reserve.bits).reduce(_ || _))
  }
  for (i <- 0 until 2) {
    when(io.kill(i) || (io.retire.fire && atHead(i))) {
      reserved(i) := false.B
      captured(i) := false.B
    }
    when(io.reserve.valid && firstFree(i)) {
      reserved(i) := true.B
      captured(i) := false.B
      tags(i) := io.reserve.bits
    }
    when(io.capture.fire && matches(i)) {
      data(i) := io.capture.bits.data
      captured(i) := true.B
    }
  }
}
