package xiangshan.evu.backend

import chisel3._
import chisel3.util._

/** One committed VRF group shared by the ordinary PACK operand collectors. */
class PackDescriptorLine(egBits: Int, dataBits: Int, clients: Int, banks: Int) extends Module {
  val io = IO(new Bundle {
    val fill = Input(Vec(clients, Valid(new Bundle {
      val eg = UInt(egBits.W)
      val data = UInt(dataBits.W)
    })))
    val invalidate = Input(Vec(banks, Valid(UInt(egBits.W))))
    val tag = Output(Valid(UInt(egBits.W)))
    val data = Output(UInt(dataBits.W))
  })
  val valid = RegInit(false.B)
  val eg = Reg(UInt(egBits.W))
  val data = Reg(UInt(dataBits.W))
  def changed(tag: UInt): Bool = io.invalidate.map(w => w.valid && w.bits === tag).reduce(_ || _)
  io.tag.valid := valid && !changed(eg) && !reset.asBool
  io.tag.bits := eg
  io.data := data
  val fills = io.fill.map(f => f.valid && !changed(f.bits.eg))
  when(changed(eg)) { valid := false.B }
  when(fills.reduce(_ || _)) {
    val selected = PriorityMux(fills, io.fill.map(_.bits))
    valid := true.B
    eg := selected.eg
    data := selected.data
  }
}
