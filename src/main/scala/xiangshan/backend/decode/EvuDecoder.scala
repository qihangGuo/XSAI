package xiangshan.backend.decode

import chisel3._
import chisel3.util.BitPat
import freechips.rocketchip.util.uintToBitPat
import xiangshan.{SelImm, SrcType, UopSplitType}
import xiangshan.backend.fu.FuType

/** XV uses custom0, funct7 for the operation and funct3 for the SEW. */
object EvuDecoder extends DecodeConstants {
  private def xv(funct7: Int): BitPat = {
    val bits = String.format("%7s", Integer.toBinaryString(funct7)).replace(' ', '0')
    BitPat("b" + bits + "?????" + "?????" + "???" + "?????" + "0001011")
  }

  private def op(funct7: Int, scalarBase: Boolean = false): (BitPat, XSDecodeBase) = {
    val src = if (scalarBase) SrcType.reg else SrcType.no
    // Memory preflight remains non-speculative. The ROB releases an ordinary
    // memory backward barrier at scalar retirement; context changes and traps
    // separately drain retired EVU work through the final memory Ack.
    val barrier = Set(0x10, 0x11, 0x18, 0x19, 0x1a, 0x45, 0x46, 0x52, 0x53).contains(funct7)
    xv(funct7) -> XSDecode(src, SrcType.no, SrcType.X, FuType.evu,
      funct7.U, SelImm.IMM_EVU, UopSplitType.X, xWen = false, noSpec = barrier, blockBack = barrier)
  }

  // WAIT/FENCE stop younger dispatch until the ROB and EVU have drained.
  override val decodeArray: Array[(BitPat, XSDecodeBase)] = Array(
    op(1), op(2), op(3), op(4), op(5), op(6), op(7),
    op(12), op(13), op(14), op(0x0f),
    op(0x10, scalarBase = true), op(0x11, scalarBase = true),
    op(0x18, scalarBase = true), op(0x19, scalarBase = true), op(0x1a),
    op(0x45, scalarBase = true), op(0x46, scalarBase = true),
    op(0x47, scalarBase = true), op(0x48, scalarBase = true),
    op(0x50, scalarBase = true), op(0x51),
    op(0x52, scalarBase = true), op(0x53, scalarBase = true),
    op(0x54), op(0x55), op(0x56), op(0x57), op(0x58), op(0x59),
    op(0x5a), op(0x5b), op(0x5c), op(0x5d), op(0x5e, scalarBase = true),
    op(0x20), op(0x21), op(0x22), op(0x23), op(0x24), op(0x25), op(0x26),
    op(0x30), op(0x31), op(0x32), op(0x33), op(0x34), op(0x35), op(0x36), op(0x37),
    op(0x39), op(0x3a), op(0x3b), op(0x3c), op(0x3d), op(0x3e), op(0x3f),
    op(0x40), op(0x41), op(0x42), op(0x43), op(0x44)
  )

}
