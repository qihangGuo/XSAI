package xiangshan.evu

import chisel3._
import chisel3.util._

object EvuOpcode {
  val Invalid = 0.U(8.W)
  val Add     = 1.U(8.W)
  val Sub     = 2.U(8.W)
  val And     = 3.U(8.W)
  val Or      = 4.U(8.W)
  val Xor     = 5.U(8.W)
  val Mul     = 6.U(8.W)
  val Mac     = 7.U(8.W)
  // Retired VRF quantization opcodes 0x09 and 0x0b remain reserved.
  val ReduceSum = 12.U(8.W)
  val ReduceMax = 13.U(8.W)
  val ReduceMin = 14.U(8.W)
  val Exp2      = 15.U(8.W)
  val Sll       = 0x20.U(8.W)
  val Srl       = 0x21.U(8.W)
  val Sra       = 0x22.U(8.W)
  val Min       = 0x23.U(8.W)
  val Max       = 0x24.U(8.W)
  val MinU      = 0x25.U(8.W)
  val MaxU      = 0x26.U(8.W)
  val FAdd      = 0x30.U(8.W)
  val FSub      = 0x31.U(8.W)
  val FMul      = 0x32.U(8.W)
  val FMacc     = 0x33.U(8.W)
  val FDiv      = 0x34.U(8.W)
  val FMin      = 0x35.U(8.W)
  val FMax      = 0x36.U(8.W)
  val FReduceSum = 0x37.U(8.W)
  // 0x38 is reserved (the dedicated Softmax operation was removed).
  val WAdd      = 0x39.U(8.W)
  val WSub      = 0x3a.U(8.W)
  val WMul      = 0x3b.U(8.W)
  val SatAdd    = 0x3c.U(8.W)
  val SatSub    = 0x3d.U(8.W)
  val SlideUp   = 0x3e.U(8.W)
  val SlideDown = 0x3f.U(8.W)
  val ReduceAnd = 0x40.U(8.W)
  val ReduceOr  = 0x41.U(8.W)
  val ReduceXor = 0x42.U(8.W)
  val FReduceMin = 0x43.U(8.W)
  val FReduceMax = 0x44.U(8.W)
  val Load    = 0x10.U(8.W)
  val Store   = 0x11.U(8.W)
  // 0x18 (Launch) is retired; it must not acquire a control context.
  val Fence   = 0x1a.U(8.W)
  val SetVli  = 0x45.U(8.W)
  val SetVl   = 0x46.U(8.W)
  val Gather  = 0x47.U(8.W)
  val Scatter = 0x48.U(8.W)
  val GatherImm = 0x51.U(8.W)
  val FSqrt = 0x60.U(8.W)
  val FRSqrt7 = 0x61.U(8.W)
  val FRec7 = 0x64.U(8.W)
  val FSgnjX = 0x66.U(8.W)
  val FWCvtFF = 0x67.U(8.W)
  val FNCvtFF = 0x68.U(8.W)
  val FCvtXF = 0x69.U(8.W)
  val FCvtFX = 0x6a.U(8.W)
  val NClipImm = 0x6b.U(8.W)
  val ZExt2 = 0x6c.U(8.W)
  val SExt2 = 0x6d.U(8.W)
  val NSrlImm = 0x6e.U(8.W)
  val CvtI8F32 = 0x70.U(8.W)
  val CvtE4M3F32 = 0x71.U(8.W)
  val CvtE5M2F32 = 0x72.U(8.W)
  val CvtF32E4M3 = 0x73.U(8.W)
  val CvtMXFP8F32 = 0x74.U(8.W)
  val CvtMXFP4F32 = 0x75.U(8.W)
  // Retired fixed-point QuantToCute (0x4c) and SETCFG (0x4e) are reserved.
  // Retired VRF quantization opcode 0x4f remains reserved.
}

class EvuMemoryRequest(p: EvuParams) extends Bundle {
  val jobId = UInt(log2Ceil(p.commandDepth).W)
  val epoch = UInt(2.W)
  val addr  = UInt(64.W)
  val data  = UInt(p.mlenBits.W)
  // Byte enables for the logical MLEN request, including partial stores.
  val mask  = UInt(p.mlenBytes.W)
  val write = Bool()
  val tag   = UInt(p.memoryTagWidth.W)
}

class EvuMemoryResponse(p: EvuParams) extends Bundle {
  val data  = UInt(p.mlenBits.W)
  val error = Bool()
  val fault = UInt(4.W)
  val tag   = UInt(p.memoryTagWidth.W)
}

class EvuMemoryIO(p: EvuParams) extends Bundle {
  val req  = Decoupled(new EvuMemoryRequest(p))
  val resp = Flipped(Decoupled(new EvuMemoryResponse(p)))
}

class EvuControlSequencerIO(p: EvuParams) extends Bundle {
  val command    = Flipped(Decoupled(new EvuCommand(p)))
  val completion = Decoupled(new EvuCompletion(p))
  val flush      = Input(Bool())
  val busy       = Output(Bool())
  val blockYounger = Output(Bool())
  val backendBusy = Input(Bool())
}

/**
  * Serialized control sequencer for Fence and VL/VTYPE updates.
  *
  * All vector arithmetic and memory traffic run through the Saturn subsystem.
  * FENCE waits for the vector backend and physical memory transactions.
  * Arithmetic flags and faults are published by their owning commands.
  */
class EvuControlSequencer(p: EvuParams) extends Module {
  val io = IO(new EvuControlSequencerIO(p))

  private val active = RegInit(false.B)
  private val cmdReg = Reg(new EvuCommand(p))

  private def isControl(cmd: EvuCommand): Bool = cmd.opcode === EvuOpcode.Fence ||
    cmd.opcode === EvuOpcode.SetVli || cmd.opcode === EvuOpcode.SetVl

  private val fenceDone = cmdReg.opcode === EvuOpcode.Fence && !io.backendBusy
  private val immediateDone = cmdReg.opcode === EvuOpcode.SetVli || cmdReg.opcode === EvuOpcode.SetVl
  private val completionDone = active && isControl(cmdReg) && (immediateDone || fenceDone)

  io.command.ready := !active && !io.flush
  // Unlike busy, this is registered-state-only: Top may use it to gate the
  // shared input without forming a command.valid -> busy combinational loop.
  io.blockYounger := active && cmdReg.opcode === EvuOpcode.Fence
  when(io.command.fire) {
    cmdReg := io.command.bits
    active := true.B
  }

  io.completion.valid := completionDone && !io.flush
  io.completion.bits.pc := cmdReg.pc
  io.completion.bits.tval := 0.U
  io.completion.bits.tvalValid := false.B
  io.completion.bits.opcode := cmdReg.opcode
  io.completion.bits.vd := cmdReg.vd
  io.completion.bits.exception := false.B
  io.completion.bits.fflags := 0.U
  io.completion.bits.vxsat := false.B
  io.completion.bits.dirtyVs := cmdReg.opcode === EvuOpcode.SetVli || cmdReg.opcode === EvuOpcode.SetVl
  io.completion.bits.cause := EvuFault.None
  io.completion.bits.jobId := cmdReg.jobId
  io.completion.bits.epoch := cmdReg.epoch

  when(io.completion.fire) {
    active := false.B
  }
  when(io.flush) {
    active := false.B
  }

  io.busy := active || io.command.valid || io.completion.valid
}
