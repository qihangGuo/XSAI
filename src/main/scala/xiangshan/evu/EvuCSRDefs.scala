package xiangshan.evu

import chisel3._
import chisel3.util._

/** Version 2: returned-VL configuration instructions, no quant descriptors. */
object EvuCSRs {
  val abiVersion = 2
  val cfg = 0x8c0
  val fflags = 0x8c1
  val vxsat = 0x8c2
  val state = 0xbc8
  val vlenb = 0xcd0
  val capability = 0xcd1
  val status = 0xcd2
  val cause = 0xcd3
  val epc = 0xcd4
  val tval = 0xcd5
  val addresses: Seq[Int] = Seq(cfg, fflags, vxsat) ++
    Seq(state, vlenb, capability, status, cause, epc, tval)
  val snapshotWords = 13 // vector views, flags/state, error records, EVCFG

  def contains(addr: UInt): Bool = addresses.map(a => addr === a.U).reduce(_ || _)
  def needsDrain(addr: UInt): Bool = Seq(cfg, fflags, vxsat, state, status, cause, epc, tval)
    .map(a => addr === a.U).reduce(_ || _)

  def capabilityValue(p: EvuParams): BigInt =
    BigInt(abiVersion) | (BigInt(7) << 16) |
      (BigInt(1) << 24) | // bit 25 is reserved after retirement of direct quantization
      (BigInt(1) << 26) | (BigInt(1) << 27) | (BigInt(1) << 28) |
      EvuLowPrecision.entries.map(_.capabilityBit).distinct.map(bit => BigInt(1) << bit).reduce(_ | _) |
      (if (p.dlenBits == 512 && p.splitFpIssue && p.fp32MinMaxTree) BigInt(1) << 30 else BigInt(0))
}

/** Shared by scalar VL writeback and the authoritative retired CSR update. */
object EvuConfigRules {
  def requestedType(instruction: UInt, registerType: UInt): UInt =
    Mux(instruction(31, 25) === EvuIsa.setvli,
      Cat(instruction(14, 12), instruction(24, 20)).pad(64), registerType)

  def normalize(p: EvuParams, avl: UInt, requestedType: UInt,
      enable: Bool = true.B, forceVill: Bool = false.B): EvuExecutionConfig = {
    val result = Wire(new EvuExecutionConfig(p))
    val requested = requestedType.pad(64)
    val sew = requested(5, 3)
    val lmul = requested(2, 0)
    val base = p.vlenBytes.U(32.W) >> sew
    val maximum = Mux(lmul(2), base >> (8.U - lmul), base << lmul(1, 0))
    val illegal = forceVill || requested(63, 8).orR ||
      sew > EvuIsa.maxSupportedSew.U || lmul === 4.U || maximum === 0.U
    result.valid := true.B
    result.configured := enable
    result.vill := enable && illegal
    result.vtype := Mux(!enable, 0x10.U, Mux(illegal, 0.U, requested(7, 0)))
    result.vl := Mux(!enable, (p.vlenBits / 32).U,
      Mux(illegal, 0.U, Mux(avl > maximum, maximum, avl)))
    result
  }
}

class EvuExecutionConfig(p: EvuParams) extends Bundle {
  val configured = Bool()
  val valid = Bool() // false is reserved for the raw standalone test interface
  val vl = UInt(log2Ceil(p.vlenBits + 1).W)
  val vtype = UInt(8.W)
  val vill = Bool()
}

class EvuDrainResult extends Bundle {
  val fflags = UInt(5.W)
  val exception = Bool()
  val cause = UInt(4.W)
  val pc = UInt(64.W)
  val tval = UInt(64.W)
  val tvalValid = Bool()
}

/** One outstanding, ordered drain transaction; queues also provide CDC. */
class EvuDrainIO extends Bundle {
  val request = Decoupled(Bool())
  val response = Flipped(Decoupled(new EvuDrainResult))
}

/** The general barrier stays intact; only a checked load may use the narrower one. */
object EvuPreflightReasons {
  def mayCheckAcrossBarrier(reasons: EvuPreflightReasons, stable: Bool): Bool =
    stable && !reasons.unpublishedControl && !reasons.otherCompletion &&
      (!reasons.status || reasons.arithmeticOnly)

  def blocksLoad(reasons: EvuPreflightReasons, stable: Bool): Bool =
    (reasons.control || reasons.status) && !mayCheckAcrossBarrier(reasons, stable)
  def arithmeticOnly(status: UInt, completed: Bool, arithmetic: Bool): Bool = {
    val other = RegInit(0.U(status.getWidth.W))
    val newOther = completed && !arithmetic
    other := Cat(other(status.getWidth - 2, 0), newOther)
    // A fresh non-arithmetic completion must veto even before it enters status.
    status.orR && !other.orR && !newOther
  }
}

class EvuPreflightReasons extends Bundle {
  val control = Bool()
  val unpublishedControl = Bool()
  val otherCompletion = Bool()
  val status = Bool()
  val arithmeticOnly = Bool()
}

/** CPU-only identity; the ROB tag is not part of a command sent to the EVU. */
class EvuRetiredCommand(p: EvuParams, tagWidth: Int) extends Bundle {
  val command = new EvuCoreCommand(p)
  val robTag = UInt(tagWidth.W)
}

/** CSR functional unit view. All completion/config events are non-speculative. */
class EvuCSRCoreIO(p: EvuParams, tagWidth: Int) extends Bundle {
  val busy = Input(Bool())
  val drained = Input(Bool())
  val wantDrain = Output(Bool())
  val accepted = Input(Valid(new EvuRetiredCommand(p, tagWidth)))
  val configPublished = Output(Valid(UInt(tagWidth.W)))
  val preflightConfigReady = Output(Bool())
  val completed = Input(Valid(new EvuCompletion(p)))
  val drainResult = Input(Valid(new EvuDrainResult))
  val config = Output(new EvuExecutionConfig(p))
  val configured = Output(Bool())
  val permitted = Output(Bool())
  val state = Output(UInt(2.W))
}
