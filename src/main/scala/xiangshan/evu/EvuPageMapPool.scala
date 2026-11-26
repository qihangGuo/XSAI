package xiangshan.evu

import chisel3._
import chisel3.util._

object EvuPageMapPool {
  val entries = 32

  def admission(occupied: UInt, atRob: UInt, atDispatch: UInt, width: Int): (Bool, Bool) = {
    require(width > 0 && width <= entries)
    val afterRob = occupied +& atRob
    val limit = (entries - width).U
    // Dispatch is one non-replaying register ahead of ROB enqueue. Its next
    // grant must also charge the group crossing that register on this edge.
    (RegNext(afterRob <= limit, true.B), RegNext((afterRob +& atDispatch) <= limit, true.B))
  }
}

/** Reserve at ordered ROB enqueue, not out-of-order preflight completion. */
class EvuPageMapPool(p: EvuParams, owners: Int, reservePorts: Int, writePorts: Int, readPorts: Int)
    extends Module {
  val entries = EvuPageMapPool.entries
  val io = IO(new Bundle {
    val reserve = Input(Vec(reservePorts, Valid(UInt(log2Ceil(owners).W))))
    val slots = Output(Vec(reservePorts, UInt(5.W)))
    val write = Input(Vec(writePorts, Valid(new Bundle {
      val slot = UInt(5.W)
      val owner = UInt(log2Ceil(owners).W)
      val map = new EvuPageMap(p)
    })))
    val read = Input(Vec(readPorts, UInt(5.W)))
    val readBase = Input(Vec(readPorts, UInt(64.W)))
    val data = Output(Vec(readPorts, new EvuPageMap(p)))
    val release = Input(UInt(entries.W))
    val cancelOwners = Input(UInt(owners.W))
    val occupied = Output(UInt(6.W))
  })
  val valid = RegInit(0.U(entries.W))
  val owner = Reg(Vec(entries, UInt(log2Ceil(owners).W)))
  val maps = Reg(Vec(entries, new Bundle {
    val enabled = Bool()
    val length = UInt(32.W)
    val ppn = Vec(p.translationPages, UInt(52.W))
  }))
  val choices = scala.collection.mutable.ArrayBuffer.empty[UInt]
  var available: UInt = ~valid
  for (i <- 0 until reservePorts) {
    val choice = PriorityEncoderOH(available)
    io.slots(i) := OHToUInt(choice)
    val accepted = Mux(io.reserve(i).valid, choice, 0.U(entries.W))
    choices += accepted
    when(io.reserve(i).valid) { assert(available.orR, "CPU translation reservation overflow") }
    available = available & ~accepted
  }
  val allocated = choices.reduce(_ | _)
  val cancelled = VecInit((0 until entries).map(i => valid(i) && io.cancelOwners(owner(i)))).asUInt
  valid := (valid & ~io.release & ~cancelled) | allocated
  for (i <- 0 until entries) {
    for (port <- 0 until reservePorts) {
      when(choices(port)(i)) { owner(i) := io.reserve(port).bits }
    }
    for (port <- io.write) {
      when(port.valid && port.bits.slot === i.U && !cancelled(i)) {
        assert(valid(i) && owner(i) === port.bits.owner, "CPU translation write has a stale owner")
        maps(i).enabled := port.bits.map.enabled
        maps(i).length := port.bits.map.length
        maps(i).ppn := port.bits.map.ppn
      }
    }
  }
  for (i <- 0 until readPorts) {
    val map = maps(io.read(i))
    io.data(i).enabled := map.enabled
    io.data(i).base := io.readBase(i)
    io.data(i).length := map.length
    io.data(i).ppn := map.ppn
  }
  io.occupied := PopCount(valid)
}
