package xiangshan.evu

import chisel3._
import chisel3.util._

object EvuAddressFault {
  val None = 0.U(3.W)
  val Access = 1.U(3.W)
  val Page = 2.U(3.W)
  val GuestPage = 3.U(3.W)
  val Misaligned = 4.U(3.W)
  val Illegal = 5.U(3.W)
}

class EvuRangeRequest extends Bundle {
  val base = UInt(64.W)
  val length = UInt(32.W)
  val read = Bool()
  val write = Bool()
  val alignment = UInt(3.W)
  val unbounded = Bool()
  val illegal = Bool()
}

class EvuAddressResponse extends Bundle {
  val paddr = UInt(64.W)
  val gpaddr = UInt(64.W)
  val fault = UInt(3.W)
  val nonLeaf = Bool()
}

class EvuRangeResult(p: EvuParams) extends Bundle {
  val pageMap = new EvuPageMap(p)
  val fault = UInt(3.W)
  val vaddr = UInt(64.W)
  val gpaddr = UInt(64.W)
  val nonLeaf = Bool()
  val write = Bool()
}

class EvuRangeIO(p: EvuParams) extends Bundle {
  val req = Decoupled(new EvuRangeRequest)
  val resp = Flipped(Decoupled(new EvuRangeResult(p)))
  val cancel = Output(Bool())
}

/** Check every touched cache line, including RMW read permissions, before any
  * vector or memory side effects. The host serializes address-space changes
  * until the checked command has received its final memory acknowledgement.
  */
class EvuAddressCheck(p: EvuParams) extends Module {
  val io = IO(new Bundle {
    val range = Flipped(new EvuRangeIO(p))
    val lookup = new Bundle {
      val req = Decoupled(new Bundle {
        val vaddr = UInt(64.W)
        val write = Bool()
      })
      val resp = Flipped(Decoupled(new EvuAddressResponse))
    }
    val allowPhysicalLaunch = Input(Bool())
  })
  val idle :: request :: response :: finish :: Nil = Enum(4)
  val state = RegInit(idle)
  val job = Reg(new EvuRangeRequest)
  val result = Reg(new EvuRangeResult(p))
  val cursor = Reg(UInt(64.W))
  val lastLine = Reg(UInt(64.W))
  val writePhase = RegInit(false.B)
  val mapped = RegInit(VecInit.fill(p.translationPages)(false.B))

  io.range.req.ready := state === idle && !io.range.cancel
  io.range.resp.valid := state === finish && !io.range.cancel
  io.range.resp.bits := result
  io.lookup.req.valid := state === request && !io.range.cancel
  io.lookup.req.bits.vaddr := cursor
  io.lookup.req.bits.write := writePhase
  io.lookup.resp.ready := state === response && !io.range.cancel

  when(io.range.req.fire) {
    val in = io.range.req.bits
    val end = in.base +& in.length - 1.U
    val pages = (end(63, 12) - in.base(63, 12)) +& 1.U
    val misaligned = (in.base & ((1.U(64.W) << in.alignment) - 1.U)).orR
    job := in
    result := 0.U.asTypeOf(result)
    result.pageMap.enabled := !in.unbounded && in.length =/= 0.U
    result.pageMap.base := in.base
    result.pageMap.length := in.length
    result.vaddr := in.base
    result.write := !in.read
    cursor := in.base & ~63.U(64.W)
    lastLine := end(63, 0) & ~63.U(64.W)
    writePhase := false.B
    mapped.foreach(_ := false.B)
    when(in.illegal) {
      result.fault := EvuAddressFault.Illegal
      result.pageMap.enabled := false.B
      state := finish
    }.elsewhen(in.unbounded) {
      // xv.launch describes a row stride, not the complete CUTE output span.
      result.fault := Mux(io.allowPhysicalLaunch, EvuAddressFault.None, EvuAddressFault.Illegal)
      state := finish
    }.elsewhen(in.length === 0.U) {
      state := finish
    }.elsewhen(misaligned || end(64) || pages > p.translationPages.U || in.length > (8 * p.vlenBytes).U) {
      result.fault := Mux(misaligned, EvuAddressFault.Misaligned, EvuAddressFault.Access)
      state := finish
    }.otherwise {
      state := request
    }
  }
  when(io.lookup.req.fire) { state := response }
  when(io.lookup.resp.fire) {
    val answer = io.lookup.resp.bits
    val index = cursor(63, 12) - job.base(63, 12)
    val slot = index(log2Ceil(p.translationPages) - 1, 0)
    val changed = mapped(slot) && result.pageMap.ppn(slot) =/= answer.paddr(63, 12)
    when(answer.fault =/= EvuAddressFault.None || changed) {
      result.fault := Mux(changed, EvuAddressFault.Access, answer.fault)
      result.vaddr := Mux(cursor < job.base, job.base, cursor)
      result.gpaddr := answer.gpaddr
      result.nonLeaf := answer.nonLeaf
      result.write := !job.read || writePhase
      result.pageMap.enabled := false.B
      state := finish
    }.otherwise {
      result.pageMap.ppn(slot) := answer.paddr(63, 12)
      mapped(slot) := true.B
      when(!writePhase && job.write) {
        writePhase := true.B
        state := request
      }.elsewhen(cursor === lastLine) {
        state := finish
      }.otherwise {
        cursor := cursor + 64.U
        writePhase := false.B
        state := request
      }
    }
  }
  when(io.range.resp.fire || io.range.cancel) { state := idle }
}

/** Mirror only commands already accepted after scalar retirement. The core
  * waits for EVU drain before using this state for memory preflight.
  */
class EvuMemoryConfig(p: EvuParams) extends Module {
  val io = IO(new Bundle {
    val accepted = Flipped(Valid(new EvuCoreCommand(p)))
    val command = Input(new EvuCoreCommand(p))
    val length = Output(UInt(32.W))
    val alignment = Output(UInt(3.W))
    val illegal = Output(Bool())
  })
  val configured = RegInit(false.B)
  val vl = RegInit(0.U(log2Ceil(p.vlenBits + 1).W))
  val sew = RegInit(2.U(3.W))
  val vill = RegInit(false.B)
  val accepted = io.accepted.bits
  val f7 = accepted.instr(31, 25)
  when(io.accepted.valid && (f7 === EvuIsa.setvli || f7 === EvuIsa.setvl)) {
    val (newVl, newSew, illegal) = EvuVtype.configure(p,
      f7 === EvuIsa.setvli, accepted.instr(14, 12), accepted.vl, accepted.aux)
    configured := true.B
    vl := newVl
    sew := newSew
    vill := illegal
  }
  val memory = io.command.instr(31, 25) === EvuIsa.load ||
    io.command.instr(31, 25) === EvuIsa.store
  io.alignment := Mux(memory, Mux(configured, sew, io.command.instr(14, 12)), 2.U)
  io.length := Mux(memory && configured, vl << sew, io.command.length)
  io.illegal := configured && vill
}

/** Lookup after cache-line splitting: each request belongs to one 4 KiB page. */
class EvuPageTranslate(p: EvuParams) extends Module {
  val io = IO(new Bundle {
    val pageMap = Input(new EvuPageMap(p))
    val vaddr = Input(UInt(64.W))
    val paddr = Output(UInt(64.W))
    val legal = Output(Bool())
  })
  val map = io.pageMap
  val index = io.vaddr(63, 12) - map.base(63, 12)
  val slot = index(log2Ceil(p.translationPages) - 1, 0)
  val firstLine = map.base & ~63.U(64.W)
  val lastLine = (map.base + map.length - 1.U) & ~63.U(64.W)
  io.legal := !map.enabled || (map.length =/= 0.U && index < p.translationPages.U &&
    io.vaddr >= firstLine && io.vaddr <= lastLine && !io.vaddr(5, 0).orR)
  io.paddr := Mux(map.enabled, Cat(map.ppn(slot), io.vaddr(11, 0)), io.vaddr)
}
