package xiangshan.evu

import chisel3._
import chisel3.util._
import xiangshan.evu.common.{OPFFunct6, OPIFunct6, OPMFunct6, VectorConsts}

object EvuRvvMath {
  val operations: Seq[UInt] = Seq(EvuOpcode.FSqrt, EvuOpcode.FRSqrt7, EvuOpcode.FRec7,
    EvuOpcode.FSgnjX, EvuOpcode.FWCvtFF, EvuOpcode.FNCvtFF, EvuOpcode.FCvtXF,
    EvuOpcode.FCvtFX, EvuOpcode.NClipImm, EvuOpcode.ZExt2, EvuOpcode.SExt2, EvuOpcode.NSrlImm)

  def contains(op: UInt): Bool = operations.map(op === _).reduce(_ || _)
  def widening(op: UInt): Bool = op === EvuOpcode.FWCvtFF
  def narrowing(op: UInt): Bool = Seq(EvuOpcode.FNCvtFF, EvuOpcode.NClipImm,
    EvuOpcode.NSrlImm).map(op === _).reduce(_ || _)
  def extending(op: UInt): Bool = op === EvuOpcode.ZExt2 || op === EvuOpcode.SExt2
  def immediate(op: UInt): Bool = op === EvuOpcode.NClipImm || op === EvuOpcode.NSrlImm
  def unary(op: UInt): Bool = contains(op) && op =/= EvuOpcode.FSgnjX && !immediate(op)

  def validWidth(op: UInt, sew: UInt): Bool = MuxCase(sew === 1.U || sew === 2.U, Seq(
    (widening(op) || op === EvuOpcode.FNCvtFF) -> (sew === 1.U),
    (op === EvuOpcode.FCvtXF || op === EvuOpcode.FCvtFX) -> (sew === 2.U),
    immediate(op) -> (sew <= 1.U)))

  def registers(lmul: UInt): UInt = Mux(lmul(2), 1.U(5.W), (1.U(5.W) << lmul(1, 0))(4, 0))
  def doubledRegisters(lmul: UInt): UInt = Mux(lmul(2), 1.U(5.W), (registers(lmul) << 1)(4, 0))
  def sourceRegisters(op: UInt, lmul: UInt): UInt = Mux(narrowing(op), doubledRegisters(lmul),
    Mux(extending(op), Mux(lmul(2) || lmul === 0.U, 1.U, registers(lmul) >> 1), registers(lmul)))
  def destinationRegisters(op: UInt, lmul: UInt): UInt =
    Mux(widening(op), doubledRegisters(lmul), registers(lmul))

  def validGroups(op: UInt, dest: UInt, source1: UInt, source2: UInt, lmul: UInt): Bool = {
    val destCount = destinationRegisters(op, lmul)
    val sourceCount = sourceRegisters(op, lmul)
    def aligned(reg: UInt, count: UInt): Bool =
      count <= 8.U && (reg & (count - 1.U)) === 0.U && (reg +& count) <= 32.U
    val overlap = dest < (source2 +& sourceCount) && source2 < (dest +& destCount)
    val wideSourceAtTop = (source2 +& sourceCount) === (dest +& destCount)
    val overlapLegal = !overlap || Mux(narrowing(op), dest === source2,
      Mux(widening(op), !lmul(2) && wideSourceAtTop,
        Mux(extending(op), !lmul(2) && lmul =/= 0.U && wideSourceAtTop, true.B)))
    lmul =/= 4.U && !(extending(op) && lmul === 5.U) &&
      aligned(dest, destCount) && aligned(source2, sourceCount) && overlapLegal &&
      Mux(unary(op), source1 === 0.U, immediate(op) || aligned(source1, registers(lmul)))
  }

  def encode(cmd: EvuCommand): UInt = {
    val op = cmd.opcode
    val conversion = Seq(EvuOpcode.FWCvtFF, EvuOpcode.FNCvtFF, EvuOpcode.FCvtXF,
      EvuOpcode.FCvtFX).map(op === _).reduce(_ || _)
    val funct6 = MuxCase(OPFFunct6.funary1.asUInt, Seq(
      (op === EvuOpcode.FSgnjX) -> OPFFunct6.fsgnjx.asUInt,
      conversion -> OPFFunct6.funary0.asUInt,
      extending(op) -> OPMFunct6.xunary0.asUInt,
      (op === EvuOpcode.NClipImm) -> OPIFunct6.nclip.asUInt,
      (op === EvuOpcode.NSrlImm) -> OPIFunct6.nsrl.asUInt))
    val selector = MuxLookup(op, cmd.vs1)(Seq(
      EvuOpcode.FSqrt -> 0.U(5.W), EvuOpcode.FRSqrt7 -> 4.U(5.W), EvuOpcode.FRec7 -> 5.U(5.W),
      EvuOpcode.FWCvtFF -> 12.U(5.W), EvuOpcode.FNCvtFF -> 20.U(5.W),
      EvuOpcode.FCvtXF -> 1.U(5.W), EvuOpcode.FCvtFX -> 3.U(5.W),
      EvuOpcode.ZExt2 -> 6.U(5.W), EvuOpcode.SExt2 -> 7.U(5.W)))
    val funct3 = Mux(immediate(op), VectorConsts.OPIVI,
      Mux(extending(op), VectorConsts.OPMVV, VectorConsts.OPFVV))
    Cat(funct6, true.B, cmd.vs2, selector, funct3, cmd.vd, VectorConsts.opcVector)
  }
}
