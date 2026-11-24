package xiangshan.evu

import chisel3._
import chisel3.util._
import xiangshan.evu.common.VectorConsts

/** Packed CVT ABI. Widths describe logical elements; SEW remains e32/m1.
  * The external custom0 selector and private backend encoding are distinct.
  * Keep decode, sequencing and FU ownership derived from this table.
  */
object EvuLowPrecision {
  case class Entry(opcode: Int, funct6: Int, subop: Option[Int], format: Int,
      decode: Boolean, scaled: Boolean, sourceBits: Int, destinationBits: Int,
      capabilityBit: Int) {
    val family: String = if (format == 5) "bf16" else if (scaled) "mx" else "raw"
    def matches(f6: UInt, rs1: UInt): Bool =
      f6 === funct6.U && subop.map(s => rs1 === s.U).getOrElse(true.B)
  }
  val entries: Seq[Entry] = Seq(
    Entry(0x70, 0x38, Some(0), 0, false, false, 32, 8, 29),
    Entry(0x71, 0x39, Some(0), 2, false, false, 32, 8, 29),
    Entry(0x72, 0x3a, Some(0), 3, false, false, 32, 8, 29),
    Entry(0x73, 0x3b, Some(0), 2, true, false, 8, 32, 29),
    Entry(0x74, 0x3c, None, 2, false, true, 32, 8, 29),
    Entry(0x75, 0x3d, None, 4, false, true, 32, 4, 29),
    Entry(0x27, 0x38, Some(1), 1, false, false, 32, 8, 31),
    Entry(0x28, 0x38, Some(2), 0, true, false, 8, 32, 31),
    Entry(0x29, 0x38, Some(3), 1, true, false, 8, 32, 31),
    Entry(0x2a, 0x39, Some(1), 4, false, false, 32, 4, 31),
    Entry(0x2b, 0x3a, Some(1), 3, true, false, 8, 32, 31),
    Entry(0x2c, 0x3b, Some(1), 4, true, false, 4, 32, 31),
    Entry(0x2d, 0x3b, Some(2), 5, false, false, 32, 16, 33),
    Entry(0x2e, 0x3b, Some(3), 5, true, false, 16, 32, 33),
    Entry(0x78, 0x36, None, 3, false, true, 32, 8, 32),
    Entry(0x79, 0x32, None, 2, true, true, 8, 32, 32),
    Entry(0x7a, 0x33, None, 3, true, true, 8, 32, 32),
    Entry(0x7b, 0x34, None, 4, true, true, 4, 32, 32)
  )
  require(entries.map(_.opcode).distinct.size == entries.size)
  require(entries.map(e => (e.funct6, e.subop)).distinct.size == entries.size)
  require(entries.forall(e => e.funct6 != 0x3e && e.funct6 != 0x3f),
    "CVT must not overlap block AMAX/PACK backend tags")
  def operations: Seq[UInt] = entries.map(_.opcode.U(8.W))
  private def selected(f6: UInt, rs1: UInt, predicate: Entry => Boolean): Bool =
    entries.filter(predicate).map(_.matches(f6, rs1)).reduce(_ || _)
  private def field(f6: UInt, rs1: UInt, value: Entry => Int): UInt =
    Mux1H(entries.map(e => e.matches(f6, rs1) -> value(e).U))
  def contains(op: UInt): Bool = operations.map(op === _).reduce(_ || _)
  def scaled(op: UInt): Bool = entries.filter(_.scaled).map(e => op === e.opcode.U).reduce(_ || _)
  def unary(op: UInt): Bool = contains(op) && !scaled(op)
  def validGroups(op: UInt, dest: UInt, source1: UInt, source2: UInt, lmul: UInt): Bool =
    lmul === 0.U && dest =/= source2 && Mux(scaled(op), dest =/= source1, source1 === 0.U)
  def backend(funct3: UInt, funct6: UInt, rs1: UInt): Bool =
    funct3 === VectorConsts.OPIVV && selected(funct6, rs1, _ => true)
  def decode(f6: UInt, rs1: UInt): Bool = selected(f6, rs1, _.decode)
  def scaledBackend(f6: UInt, rs1: UInt): Bool = selected(f6, rs1, _.scaled)
  def packed(f6: UInt, rs1: UInt): Bool = selected(f6, rs1, _.destinationBits == 4)
  def sourcePacked(f6: UInt, rs1: UInt): Bool = selected(f6, rs1, _.sourceBits == 4)
  def brainFloat(f6: UInt, rs1: UInt): Bool = selected(f6, rs1, _.format == 5)
  def format(f6: UInt, rs1: UInt): UInt = field(f6, rs1, _.format)
  def sourceBits(f6: UInt, rs1: UInt): UInt = field(f6, rs1, _.sourceBits)
  def destinationBits(f6: UInt, rs1: UInt): UInt = field(f6, rs1, _.destinationBits)
  def sourceEew(f6: UInt, rs1: UInt): UInt = field(f6, rs1, e => log2Ceil(e.sourceBits.max(8) / 8))
  def destinationEew(f6: UInt, rs1: UInt): UInt = field(f6, rs1, e => log2Ceil(e.destinationBits.max(8) / 8))
  def encode(cmd: EvuCommand): UInt = Mux1H(entries.map { e =>
    val source1 = e.subop.map(_.U(5.W)).getOrElse(cmd.vs1)
    (cmd.opcode === e.opcode.U) -> Cat(e.funct6.U(6.W), true.B,
      cmd.vs2, source1, VectorConsts.OPIVV, cmd.vd, VectorConsts.opcVector)
  })
}
