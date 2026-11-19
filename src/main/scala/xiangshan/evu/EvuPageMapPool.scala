package xiangshan.evu

import chisel3._
import chisel3.util._

/** Reserve at ordered ROB enqueue, not out-of-order preflight completion. */
class EvuPageMapPool(p: EvuParams, owners: Int, reservePorts: Int, writePorts: Int, readPorts: Int)
    extends Module {
  val entries = 32
  val io = IO(new Bundle {
    val reserve = Input(Vec(reservePorts, Valid(UInt(log2Ceil(owners).W))))
    val slots = Output(Vec(reservePorts, UInt(5.W)))
    val write = Input(Vec(writePorts, Valid(new Bundle {
      val slot = UInt(5.W)
      val owner = UInt(log2Ceil(owners).W)
      val map = new EvuPageMap(p)
    })))
    val read = Input(Vec(readPorts, UInt(5.W)))
    val data = Output(Vec(readPorts, new EvuPageMap(p)))
    val release = Input(UInt(entries.W))
    val cancelOwners = Input(UInt(owners.W))
    val occupied = Output(UInt(6.W))
  })
  val valid = RegInit(0.U(entries.W))
  val owner = Reg(Vec(entries, UInt(log2Ceil(owners).W)))
  val maps = Reg(Vec(entries, new EvuPageMap(p)))
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
        maps(i) := port.bits.map
      }
    }
  }
  for (i <- 0 until readPorts) { io.data(i) := maps(io.read(i)) }
  io.occupied := PopCount(valid)
}
