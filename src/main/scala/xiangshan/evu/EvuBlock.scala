package xiangshan.evu

import chisel3._
import chisel3.util.Cat
import xiangshan.evu.common.VectorConsts
import xiangshan.evu.insns._

object EvuBlock {
  val Amax = 0x76.U(8.W)
  val Pack = 0x77.U(8.W)
  def contains(op: UInt): Bool = op === Amax || op === Pack
  def amax(f3: UInt, f6: UInt): Bool = f3 === VectorConsts.OPIVV && f6 === 0x3e.U
  def pack(f3: UInt, f6: UInt): Bool = f3 === VectorConsts.OPIVV && f6 === 0x3f.U
  def packSlot(descriptor: UInt): UInt = descriptor(13, 11)
  def encode(c: EvuCommand): UInt = Cat(Mux(c.opcode === Pack, 0x3f.U(6.W), 0x3e.U(6.W)),
    true.B, c.vs2, Mux(c.opcode === Pack, c.base(4,0), c.vs1), VectorConsts.OPIVV, c.vd, VectorConsts.opcVector)
  def instruction(isPack: Boolean): VectorInstruction = new VectorInstruction {
    val props = Seq(F6((if (isPack) 0x3f else 0x3e).U(6.W)), F3(VectorConsts.OPIVV), SEW(2.U),
      SetsWMask.N, if (isPack) ReadsVS1.Y else ReadsVS1.N)
  }
}
