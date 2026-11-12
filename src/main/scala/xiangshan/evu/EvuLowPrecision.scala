package xiangshan.evu

import chisel3._
import chisel3.util._
import xiangshan.evu.common.VectorConsts

object EvuLowPrecision {
  val operations: Seq[UInt] = Seq(EvuOpcode.CvtI8F32, EvuOpcode.CvtE4M3F32,
    EvuOpcode.CvtE5M2F32, EvuOpcode.CvtF32E4M3, EvuOpcode.CvtMXFP8F32, EvuOpcode.CvtMXFP4F32)
  def contains(op: UInt): Bool = operations.map(op === _).reduce(_ || _)
  def scaled(op: UInt): Bool = op === EvuOpcode.CvtMXFP8F32 || op === EvuOpcode.CvtMXFP4F32
  def unary(op: UInt): Bool = contains(op) && !scaled(op)
  def validGroups(op: UInt, dest: UInt, source1: UInt, source2: UInt, lmul: UInt): Bool =
    lmul === 0.U && dest =/= source2 && Mux(scaled(op), dest =/= source1, source1 === 0.U)
  def backend(funct3: UInt, funct6: UInt): Bool =
    funct3 === VectorConsts.OPIVV && funct6 >= 0x38.U && funct6 <= 0x3d.U
  def decode(funct6: UInt): Bool = funct6 === 0x3b.U
  def packed(funct6: UInt): Bool = funct6 === 0x3d.U
  def encode(cmd: EvuCommand): UInt = Cat(
    (cmd.opcode - EvuOpcode.CvtI8F32 + 0x38.U)(5, 0), true.B,
    cmd.vs2, cmd.vs1, VectorConsts.OPIVV, cmd.vd, VectorConsts.opcVector)
}
