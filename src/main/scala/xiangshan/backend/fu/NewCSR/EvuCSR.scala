package xiangshan.backend.fu.NewCSR

import chisel3._
import chisel3.util._
import utility.ZeroExt
import xiangshan.evu._

/** Authoritative EVU context. Execution-side copies are immutable command snapshots. */
class EvuCSR(p: EvuParams, tagWidth: Int) extends Module {
  val io = IO(new Bundle {
    val write = Input(Vec(EvuCSRs.addresses.size, new Bundle {
      val valid = Bool()
      val data = UInt(64.W)
    }))
    val read = Output(Vec(EvuCSRs.addresses.size, UInt(64.W)))
    val accepted = Input(Valid(new EvuRetiredCommand(p, tagWidth)))
    val configPublished = Output(Valid(UInt(tagWidth.W)))
    val completed = Input(Valid(new EvuCompletion(p)))
    val drain = Input(Valid(new EvuDrainResult))
    val busy = Input(Bool())
    val config = Output(new EvuExecutionConfig(p))
    val configured = Output(Bool())
    val state = Output(UInt(2.W))
    val userEnable = Output(Bool())
    val supervisorEnable = Output(Bool())
    val snapshot = Output(Vec(EvuCSRs.snapshotWords, UInt(64.W)))
  })
  val vl = RegInit((p.vlenBits / 32).U(log2Ceil(p.vlenBits + 1).W))
  val vtype = RegInit(0x10.U(8.W))
  val configured = RegInit(false.B)
  val vill = RegInit(false.B)
  val flags = RegInit(0.U(5.W))
  val sat = RegInit(false.B)
  val state = RegInit(0.U(2.W))
  val userEnable = RegInit(false.B)
  val supervisorEnable = RegInit(false.B)
  val errorValid = RegInit(false.B)
  val errorCause = RegInit(0.U(4.W))
  val errorPC = RegInit(0.U(64.W))
  val errorTval = RegInit(0.U(64.W))
  val errorTvalValid = RegInit(false.B)

  def dirty(): Unit = { state := 3.U }
  def configure(avl: UInt, requestedType: UInt, enable: Bool, forceVill: Bool): EvuExecutionConfig = {
    val next = EvuConfigRules.normalize(p, avl, requestedType, enable, forceVill)
    configured := next.configured
    vill := next.vill
    vtype := next.vtype
    vl := next.vl
    dirty()
    next
  }

  val writesLegalSetvli = WireInit(false.B)
  when(io.accepted.valid) {
    val cmd = io.accepted.bits.command
    val f7 = cmd.instr(31, 25)
    val setvl = f7 === EvuIsa.setvli || f7 === EvuIsa.setvl
    when(setvl) {
      // Scalar execution already computed this exact VL for integer writeback.
      assert(cmd.config.valid && cmd.config.configured, "Missing retired EVU configuration")
      val next = configure(cmd.base, cmd.config.vtype, true.B, cmd.config.vill)
      writesLegalSetvli := f7 === EvuIsa.setvli && !next.vill
    }.elsewhen(f7 =/= EvuIsa.store && f7 =/= EvuIsa.fence) {
      // Conservative dirty tracking also covers operations that later fail.
      dirty()
    }
  }
  // A direct EVCFG write has priority below. Report publication only after
  // the retired command's own write is visible on io.config.
  val publishes = writesLegalSetvli && !io.write(EvuCSRs.addresses.indexOf(EvuCSRs.cfg)).valid
  io.configPublished.valid := RegNext(publishes, false.B)
  io.configPublished.bits := RegEnable(io.accepted.bits.robTag, publishes)
  when(io.completed.valid) {
    flags := flags | io.completed.bits.fflags
    sat := sat || io.completed.bits.vxsat
    when(io.completed.bits.dirtyVs || io.completed.bits.fflags.orR || io.completed.bits.vxsat) { dirty() }
  }
  when(io.drain.valid) {
    flags := flags | io.drain.bits.fflags |
      Mux(io.completed.valid, io.completed.bits.fflags, 0.U)
    when(io.drain.bits.fflags.orR) { dirty() }
  }
  val fault = io.completed.valid && io.completed.bits.exception
  val drainFault = io.drain.valid && io.drain.bits.exception
  when(!errorValid && (fault || drainFault)) {
    errorValid := true.B
    errorCause := Mux(fault, io.completed.bits.cause, io.drain.bits.cause)
    errorPC := Mux(fault, io.completed.bits.pc, io.drain.bits.pc)
    errorTval := Mux(fault, io.completed.bits.tval, io.drain.bits.tval)
    errorTvalValid := Mux(fault, io.completed.bits.tvalValid, io.drain.bits.tvalValid)
    dirty()
  }

  for ((addr, index) <- EvuCSRs.addresses.zipWithIndex) {
    val w = io.write(index)
    when(w.valid) {
      addr match {
        case EvuCSRs.cfg => configure(w.data(31, 0), w.data(39, 32), w.data(40), w.data(63))
        case EvuCSRs.fflags => flags := w.data(4, 0); dirty()
        case EvuCSRs.vxsat => sat := w.data(0); dirty()
        case EvuCSRs.state =>
          state := w.data(1, 0)
          userEnable := w.data(2)
          supervisorEnable := w.data(3)
          // Bit 8 acknowledges the error only; no rollback/retry is implied.
          when(w.data(8)) {
            errorValid := false.B
            errorCause := 0.U
            errorPC := 0.U
            errorTval := 0.U
            errorTvalValid := false.B
          }
        case _ =>
      }
    }
  }
  val cfg = Cat(vill, 0.U(22.W), configured, vtype, 0.U((32 - vl.getWidth).W), vl)
  val status = Cat(0.U(61.W), errorTvalValid, errorValid, io.busy)
  io.read := VecInit(EvuCSRs.addresses.map {
    case EvuCSRs.cfg => cfg
    case EvuCSRs.fflags => flags.pad(64)
    case EvuCSRs.vxsat => ZeroExt(sat, 64)
    case EvuCSRs.state => Cat(0.U(60.W), supervisorEnable, userEnable, state)
    case EvuCSRs.vlenb => p.vlenBytes.U(64.W)
    case EvuCSRs.capability => EvuCSRs.capabilityValue(p).U(64.W)
    case EvuCSRs.status => status
    case EvuCSRs.cause => errorCause.pad(64)
    case EvuCSRs.epc => errorPC
    case EvuCSRs.tval => Mux(errorTvalValid, errorTval, 0.U)
    case _ => 0.U(64.W)
  })
  io.config.valid := true.B
  io.config.configured := configured
  io.config.vl := vl
  io.config.vtype := vtype
  io.config.vill := vill
  io.configured := configured
  io.state := state
  io.userEnable := userEnable
  io.supervisorEnable := supervisorEnable
  io.snapshot := VecInit(Seq(vl.pad(64), Cat(vill, 0.U(55.W), vtype),
    0.U(64.W), p.vlenBytes.U(64.W), ZeroExt(configured, 64),
    flags.pad(64), ZeroExt(sat, 64), Cat(0.U(60.W), supervisorEnable, userEnable, state),
    status & ~1.U(64.W), errorCause.pad(64), errorPC, Mux(errorTvalValid, errorTval, 0.U), cfg))
}
