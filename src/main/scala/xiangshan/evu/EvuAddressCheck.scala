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
  val fullLineWrite = Bool() // Caller-proven unmasked, aligned, full-line coverage.
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

class EvuPreflightPerf extends Bundle {
  val commands = UInt(64.W)
  val busy = UInt(64.W)
  val checks = UInt(64.W)
  val reads = UInt(64.W)
  val writes = UInt(64.W)
  val fullStores = UInt(64.W)
  val lookupStall = UInt(64.W)
  val tlbRequests = UInt(64.W)
  val tlbMisses = UInt(64.W)
  val sbWait = UInt(64.W)
}

class EvuRangeIO(p: EvuParams) extends Bundle {
  val req = Decoupled(new EvuRangeRequest)
  val resp = Flipped(Decoupled(new EvuRangeResult(p)))
  val cancel = Output(Bool())
  val perf = Option.when(p.enableDifftest)(Input(new EvuPreflightPerf))
}

class EvuAddressLookupRequest extends Bundle {
  val vaddr = UInt(64.W)
  val write = Bool()
}

/** Check every touched cache line, including RMW read permissions, before any
  * vector or memory side effects. The host serializes address-space changes
  * until the checked command has received its final memory acknowledgement.
  */
class EvuAddressCheck(p: EvuParams) extends Module {
  val io = IO(new Bundle {
    val range = Flipped(new EvuRangeIO(p))
    val lookup = new Bundle {
      val req = Decoupled(new EvuAddressLookupRequest)
      val resp = Flipped(Decoupled(new EvuAddressResponse))
    }
    val allowPhysicalLaunch = Input(Bool())
  })
  val idle :: checking :: finish :: Nil = Enum(3)
  val state = RegInit(idle)
  val job = Reg(new EvuRangeRequest)
  val result = Reg(new EvuRangeResult(p))
  val cursor = Reg(UInt(64.W))
  val lastLine = Reg(UInt(64.W))
  val writePhase = RegInit(false.B)
  val mapped = RegInit(VecInit.fill(p.translationPages)(false.B))
  val issuedAll = RegInit(false.B)
  val failed = RegInit(false.B)
  val responseFailed = WireDefault(false.B)
  val pending = Module(new Queue(new EvuAddressLookupRequest, 8, pipe = true, hasFlush = true))
  pending.io.flush.get := io.range.cancel
  io.range.perf.foreach(_ := 0.U.asTypeOf(new EvuPreflightPerf))

  io.range.req.ready := state === idle && !io.range.cancel
  io.range.resp.valid := state === finish && !io.range.cancel
  io.range.resp.bits := result
  io.lookup.req.valid := state === checking && !issuedAll && !failed &&
    pending.io.enq.ready && !io.range.cancel
  io.lookup.req.bits.vaddr := cursor
  io.lookup.req.bits.write := writePhase
  pending.io.enq.valid := io.lookup.req.fire
  pending.io.enq.bits := io.lookup.req.bits
  io.lookup.resp.ready := state === checking && pending.io.deq.valid && !io.range.cancel
  pending.io.deq.ready := io.lookup.resp.fire

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
    writePhase := in.fullLineWrite && in.write && !in.read
    issuedAll := false.B
    failed := false.B
    mapped.foreach(_ := false.B)
    when(in.fullLineWrite) {
      assert(in.write && !in.read && !in.base(5, 0).orR && !in.length(5, 0).orR,
        "Write-only preflight requires a proven full-line store")
    }
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
      state := checking
    }
  }
  when(io.lookup.req.fire) {
    when(!writePhase && job.write) {
      writePhase := true.B
    }.elsewhen(cursor === lastLine) {
      issuedAll := true.B
    }.otherwise {
      cursor := cursor + 64.U
      writePhase := job.fullLineWrite && job.write && !job.read
    }
  }
  when(io.lookup.resp.fire) {
    val answer = io.lookup.resp.bits
    val checked = pending.io.deq.bits
    val index = checked.vaddr(63, 12) - job.base(63, 12)
    val slot = index(log2Ceil(p.translationPages) - 1, 0)
    val changed = mapped(slot) && result.pageMap.ppn(slot) =/= answer.paddr(63, 12)
    when(!failed && (answer.fault =/= EvuAddressFault.None || changed)) {
      responseFailed := true.B
      result.fault := Mux(changed, EvuAddressFault.Access, answer.fault)
      result.vaddr := Mux(checked.vaddr < job.base, job.base, checked.vaddr)
      result.gpaddr := answer.gpaddr
      result.nonLeaf := answer.nonLeaf
      result.write := !job.read || checked.write
      result.pageMap.enabled := false.B
      failed := true.B
    }.elsewhen(!failed) {
      result.pageMap.ppn(slot) := answer.paddr(63, 12)
      mapped(slot) := true.B
    }
  }
  // Stop issuing after the first ordered fault, but drain every accepted lookup.
  val lastResponse = pending.io.count === 1.U && io.lookup.resp.fire && !io.lookup.req.fire
  when(state === checking && (issuedAll || failed || responseFailed) &&
    (!pending.io.deq.valid || lastResponse)) { state := finish }
  when(io.range.resp.fire || io.range.cancel) {
    state := idle
    issuedAll := false.B
    failed := false.B
  }
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
  val commandF7 = io.command.instr(31, 25)
  val memory = EvuIsa.isLoad(commandF7) || EvuIsa.isStore(commandF7)
  io.alignment := Mux(memory, Mux(configured, sew, io.command.instr(14, 12)), 2.U)
  io.length := Mux(EvuIsa.isElementZero(commandF7), Mux(configured && vl === 0.U, 0.U, 4.U),
    Mux(memory && configured, vl << sew, io.command.length))
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
