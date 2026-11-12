package xiangshan.evu

import chisel3._
import chisel3.util._
import xiangshan.evu.common.{OPIFunct6, VectorConsts}

object EvuSaturnSlide {
  def validGroups(op: UInt, dest: UInt, source: UInt, lmul: UInt): Bool = {
    val count = Mux(lmul(2), 1.U(5.W), (1.U(5.W) << lmul(1, 0))(4, 0))
    def aligned(reg: UInt): Bool = (reg & (count - 1.U)) === 0.U && (reg +& count) <= 32.U
    val overlap = dest < (source +& count) && source < (dest +& count)
    lmul =/= 4.U && aligned(dest) && aligned(source) && (op =/= EvuOpcode.SlideUp || !overlap)
  }

  def encode(cmd: EvuCommand): UInt = Cat(
    Mux(cmd.opcode === EvuOpcode.SlideUp, OPIFunct6.slideup.asUInt, OPIFunct6.slidedown.asUInt),
    Mux(cmd.aux(1), cmd.aux(0), true.B),
    cmd.vs2, cmd.vs1, VectorConsts.OPIVX, cmd.vd, VectorConsts.opcVector
  )
}
