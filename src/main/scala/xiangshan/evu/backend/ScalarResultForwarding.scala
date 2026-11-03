package xiangshan.evu.backend

import chisel3._
import chisel3.util._

class ScalarResultWrite(registers: Int) extends Bundle {
  val register = UInt(log2Ceil(registers).W)
  val data = UInt(32.W)
  val mask = UInt(4.W)
}

/** Mirrors only committed element-zero bytes; architectural hazards still gate readers. */
class ScalarResultForwarding(registers: Int, writers: Int) extends Module {
  val io = IO(new Bundle {
    val writes = Input(Vec(writers, Valid(new ScalarResultWrite(registers))))
    val values = Output(Vec(registers, Valid(UInt(32.W))))
  })
  val bytes = Reg(Vec(registers, Vec(4, UInt(8.W))))
  val validBytes = RegInit(VecInit.fill(registers)(0.U(4.W)))
  for (r <- 0 until registers) {
    val matches = io.writes.map(w => w.valid && w.bits.register === r.U)
    val write = Mux1H(matches, io.writes.map(_.bits))
    when(matches.reduce(_ || _)) {
      assert(PopCount(matches) === 1.U, "multiple committed writes to one EVU E0")
      validBytes(r) := validBytes(r) | write.mask
      for (b <- 0 until 4) {
        when(write.mask(b)) { bytes(r)(b) := write.data(8 * b + 7, 8 * b) }
      }
    }
    io.values(r).valid := validBytes(r).andR && !reset.asBool
    io.values(r).bits := bytes(r).asUInt
  }
}
