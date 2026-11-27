package xiangshan.backend.decode

import chisel3._
import chisel3.util.BitPat
import freechips.rocketchip.util.uintToBitPat
import xiangshan.{SelImm, SrcType, UopSplitType}
import xiangshan.backend.fu.FuType
import xiangshan.evu.EvuIsa

/** XV uses custom0, funct7 for the operation and funct3 for the SEW. */
object EvuDecoder extends DecodeConstants {
  private def xv(funct7: Int): BitPat = {
    val bits = String.format("%7s", Integer.toBinaryString(funct7)).replace(' ', '0')
    BitPat("b" + bits + "?????" + "?????" + "???" + "?????" + "0001011")
  }

  private def op(funct7: Int, scalarBase: Boolean = false): (BitPat, XSDecodeBase) = {
    val src = if (scalarBase) SrcType.reg else SrcType.no
    val barrier = Set(0x11, 0x1a, 0x53).contains(funct7)
    xv(funct7) -> XSDecode(src, SrcType.no, SrcType.X, FuType.evu,
      funct7.U, SelImm.IMM_EVU, UopSplitType.X, xWen = false, noSpec = barrier, blockBack = barrier)
  }

  private def config(registerType: Boolean): (BitPat, XSDecodeBase) = {
    val funct7 = if (registerType) 0x46 else 0x45
    // Immediate VTYPE occupies funct3:rs2. Register VTYPE uses scalar rs2 and funct3=7.
    val encoding = if (registerType) BitPat("b1000110??????????111?????0001011") else xv(funct7)
    encoding -> XSDecode(SrcType.reg, if (registerType) SrcType.reg else SrcType.no,
      SrcType.X, FuType.evu, funct7.U, SelImm.IMM_EVU, UopSplitType.X,
      xWen = true, noSpec = true, blockBack = true)
  }

  // FENCE stops younger dispatch until the ROB and EVU have drained.
  override val decodeArray: Array[(BitPat, XSDecodeBase)] = Array(
    op(1), op(2), op(3), op(4), op(5), op(6), op(7),
    op(12), op(13), op(14), op(0x0f),
    op(0x11, scalarBase = true),
    op(0x1a),
    config(registerType = false), config(registerType = true),
    op(0x47, scalarBase = true), op(0x48, scalarBase = true),
    op(0x51),
    op(0x53, scalarBase = true),
    op(0x54), op(0x55), op(0x56), op(0x57), op(0x58), op(0x59),
    op(0x5a), op(0x5b), op(0x5c), op(0x5d),
    op(0x20), op(0x21), op(0x22), op(0x23), op(0x24), op(0x25), op(0x26),
    op(0x30), op(0x31), op(0x32), op(0x33), op(0x34), op(0x35), op(0x36), op(0x37),
    op(0x39), op(0x3a), op(0x3b), op(0x3c), op(0x3d),
    op(0x3e, scalarBase = true), op(0x3f, scalarBase = true),
    op(0x40), op(0x41), op(0x42), op(0x43), op(0x44),
    op(0x60), op(0x61), op(0x62), op(0x63), op(0x64), op(0x65), op(0x66),
    op(0x67), op(0x68), op(0x69), op(0x6a), op(0x6b), op(0x6c), op(0x6d), op(0x6e), op(0x76), op(0x77, scalarBase = true)
  ) ++ EvuIsa.loadForms.map(f7 => op(f7.litValue.toInt, scalarBase = true)) ++
    xiangshan.evu.EvuLowPrecision.entries.map(e => op(e.opcode)).toArray

}
