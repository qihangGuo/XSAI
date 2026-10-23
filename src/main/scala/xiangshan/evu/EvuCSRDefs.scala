package xiangshan.evu

import chisel3._
import chisel3.util._

/** Version 1 of the independent, RV64 external-vector context interface. */
object EvuCSRs {
  val cfg = 0x8c0
  val fflags = 0x8c1
  val vxsat = 0x8c2
  val descriptorBase = 0x8d0
  val state = 0xbc8
  val vlenb = 0xcd0
  val capability = 0xcd1
  val status = 0xcd2
  val cause = 0xcd3
  val epc = 0xcd4
  val tval = 0xcd5
  val descriptorCount = 8
  val addresses: Seq[Int] = Seq(cfg, fflags, vxsat) ++
    (0 until descriptorCount).map(descriptorBase + _) ++
    Seq(state, vlenb, capability, status, cause, epc, tval)
  val snapshotWords = 21 // legacy five views, flags/sat/state, error triple/status, eight descriptors

  def contains(addr: UInt): Bool = addresses.map(a => addr === a.U).reduce(_ || _)
  def needsDrain(addr: UInt): Bool = Seq(cfg, fflags, vxsat, state, status, cause, epc, tval)
    .map(a => addr === a.U).reduce(_ || _) ||
    (addr >= descriptorBase.U && addr < (descriptorBase + descriptorCount).U)

  def capabilityValue(p: EvuParams): BigInt =
    BigInt(1) | (BigInt(descriptorCount) << 8) | (BigInt(7) << 16) |
      (BigInt(1) << 24) // ABI 1, eight descriptors, E8/E16/E32, fixed RNE
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

/** CSR functional unit view. All completion/config events are non-speculative. */
class EvuCSRCoreIO(p: EvuParams) extends Bundle {
  val busy = Input(Bool())
  val drained = Input(Bool())
  val wantDrain = Output(Bool())
  val accepted = Input(Valid(new EvuCoreCommand(p)))
  val completed = Input(Valid(new EvuCompletion(p)))
  val drainResult = Input(Valid(new EvuDrainResult))
  val config = Output(new EvuExecutionConfig(p))
  val configured = Output(Bool())
  val descriptors = Output(Vec(EvuCSRs.descriptorCount, UInt(51.W)))
  val permitted = Output(Bool())
  val state = Output(UInt(2.W))
}
