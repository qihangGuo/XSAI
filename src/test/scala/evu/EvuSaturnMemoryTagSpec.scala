package evu

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.TileVisibilityNodeKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

import scala.collection.mutable

class EvuSaturnMemoryTagHarness(val ep: EvuParams, val integerOverlapOnly: Boolean = false)(implicit p: Parameters) extends LazyModule {
  val visibility = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("memory-tag-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(
    Seq(TLSlaveParameters.v1(address = Seq(AddressSet(0, 0xffff)),
      supportsGet = TransferSizes(1, 64), supportsPutFull = TransferSizes(1, 64))), beatBytes = 64)))
  sink := visibility
  lazy val module = new EvuSaturnMemoryTagHarnessImp(this)
}

class EvuSaturnMemoryTagHarnessImp(outer: EvuSaturnMemoryTagHarness)(implicit p: Parameters)
    extends LazyModuleImp(outer) {
  val ep = outer.ep
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new EvuCommand(ep)))
    val completion = Decoupled(new EvuCompletion(ep))
    val memory = new EvuMemoryIO(ep)
    val vrf = Output(Vec(ep.vectorRegs * ep.vlenBits / ep.dlenBits, UInt(ep.dlenBits.W)))
    val flush = Input(Bool())
    val busy = Output(Bool())
    val overlap = Output(UInt(64.W))
  })
  val backend = Module(new EvuVectorBackend(ep)(p.alterPartial {
    case TileVisibilityNodeKey => outer.visibility
  }))
  backend.io.command <> io.command
  if (outer.integerOverlapOnly) {
    // Constrain the dynamic decode so FIRRTL can prune unexercised FP and
    // quant datapaths from this Treadle test, not from production hardware.
    backend.io.command.bits.opcode := Mux(io.command.bits.opcode === EvuOpcode.Load, EvuOpcode.Load,
      Mux(io.command.bits.opcode === EvuOpcode.Store, EvuOpcode.Store, EvuOpcode.Add))
    backend.io.command.bits.sew := 2.U
    backend.io.command.bits.vl := (ep.vlenBits / 32).U
    backend.io.command.bits.aux := 0.U
    backend.io.command.bits.stride := 0.S
  }
  io.completion <> backend.io.completion
  io.memory <> backend.io.memory
  io.vrf := backend.io.debugVrf.get
  backend.io.flush := io.flush
  io.busy := backend.io.busy
  io.overlap := backend.io.memoryComputeOverlap
  backend.io.vta := false.B
  backend.io.vma := false.B
  backend.io.vlmulSign := false.B
  backend.io.vlmulMag := 0.U
  backend.io.vstart := 0.U
  backend.io.vill := false.B
  backend.io.cuteWrite.ready := true.B
  backend.io.matrixInput.valid := false.B
  backend.io.matrixInput.bits := 0.U.asTypeOf(backend.io.matrixInput.bits)
  backend.io.matrixOutput.ready := true.B

  val bus = outer.visibility.out.head._1
  bus.a.valid := false.B
  bus.a.bits := 0.U.asTypeOf(bus.a.bits)
  bus.d.ready := true.B
  val manager = outer.sink.in.head._1
  manager.a.ready := true.B
  manager.d.valid := false.B
  manager.d.bits := 0.U.asTypeOf(manager.d.bits)
}

class EvuSaturnMemoryTagSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Saturn memory tag remapping"

  it should "complete 512-byte loads and stores with tag reuse, reordering and queue wraparound" in {
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    val ep = EvuParams(enable = true, enableDifftest = true)
    test(LazyModule(new EvuSaturnMemoryTagHarness(ep)).module)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.clock.setTimeout(10000)
        dut.io.command.valid.poke(false.B)
        dut.io.flush.poke(false.B)
        dut.io.completion.ready.poke(false.B)
        dut.io.memory.req.ready.poke(false.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)

        case class Response(tag: BigInt, data: BigInt, readyAt: Int, error: Boolean)
        val pending = mutable.ArrayBuffer.empty[Response]
        var response = Option.empty[Response]
        val memory = mutable.Map.empty[BigInt, BigInt]
        val accesses = mutable.ArrayBuffer.empty[(BigInt, Boolean)]
        var cycles = 0
        var lastStoreAddress = Option.empty[BigInt]
        var failLastStore = false

        def tick(): Unit = {
          // Return each batch backwards while keeping a stalled response stable.
          val eligible = pending.indices.filter(i => pending(i).readyAt <= cycles)
          if (response.isEmpty && eligible.nonEmpty &&
              (pending.size == ep.maxMemOutstanding || cycles % 7 == 0)) {
            response = Some(pending.remove(eligible.last))
          }
          dut.io.memory.resp.valid.poke(response.nonEmpty.B)
          response.foreach { r =>
            dut.io.memory.resp.bits.tag.poke(r.tag.U)
            dut.io.memory.resp.bits.data.poke(r.data.U)
            dut.io.memory.resp.bits.error.poke(r.error.B)
            dut.io.memory.resp.bits.fault.poke(if (r.error) EvuFault.Bus else EvuFault.None)
          }
          dut.io.memory.req.ready.poke((cycles % 5 != 0).B)
          val responseTaken = response.nonEmpty && dut.io.memory.resp.ready.peek().litToBoolean
          if (dut.io.memory.req.valid.peek().litToBoolean && dut.io.memory.req.ready.peek().litToBoolean) {
            val req = dut.io.memory.req.bits
            val tag = req.tag.peek().litValue
            tag should be < BigInt(ep.maxMemOutstanding)
            (pending.map(_.tag) ++ response.map(_.tag)) should not contain tag
            val addr = req.addr.peek().litValue
            val write = req.write.peek().litToBoolean
            req.mask.expect(((BigInt(1) << ep.mlenBytes) - 1).U)
            accesses += ((addr, write))
            if (write) memory(addr) = req.data.peek().litValue
            val delay = if (write && lastStoreAddress.contains(addr)) 80 else 0
            pending += Response(tag, if (write) BigInt(0) else memory(addr), cycles + delay,
              delay != 0 && failLastStore)
          }
          dut.clock.step()
          if (responseTaken) response = None
          cycles += 1
        }

        def issue(store: Boolean, reg: Int, base: Int, job: Int, sew: Int,
                  error: Boolean = false, zeroVl: Boolean = false): Unit = {
          lastStoreAddress = Option.when(store)(BigInt(base + ep.vlenBytes - ep.mlenBytes))
          failLastStore = error
          val cmd = dut.io.command.bits
          cmd.opcode.poke(if (store) EvuOpcode.Store else EvuOpcode.Load)
          cmd.sew.poke(sew.U)
          cmd.vd.poke(reg.U)
          cmd.vs1.poke(0.U)
          cmd.vs2.poke(0.U)
          cmd.vl.poke((if (zeroVl) 0 else ep.vlenBytes >> sew).U)
          cmd.base.poke(base.U)
          cmd.stride.poke(0.S)
          cmd.length.poke(ep.vlenBytes.U)
          cmd.aux.poke(0.U)
          cmd.write.poke(store.B)
          cmd.chain.poke(false.B)
          cmd.jobId.poke(job.U)
          cmd.epoch.poke(0.U)
          cmd.pageMap.enabled.poke(false.B)
          cmd.pageMap.base.poke(0.U)
          cmd.pageMap.length.poke(0.U)
          cmd.pageMap.ppn.foreach(_.poke(0.U))
          dut.io.command.valid.poke(true.B)
          var waited = 0
          while (!dut.io.command.ready.peek().litToBoolean && waited < 1500) {
            tick()
            waited += 1
          }
          dut.io.command.ready.expect(true.B)
          tick()
          dut.io.command.valid.poke(false.B)
          waited = 0
          while (!dut.io.completion.valid.peek().litToBoolean && waited < 1500) {
            tick()
            waited += 1
          }
          dut.io.completion.valid.expect(true.B)
          dut.io.completion.bits.opcode.expect(if (store) EvuOpcode.Store else EvuOpcode.Load)
          dut.io.completion.bits.vd.expect(reg.U)
          dut.io.completion.bits.jobId.expect(job.U)
          dut.io.completion.bits.exception.expect(error.B)
          dut.io.completion.bits.dirtyVs.expect((!store && !zeroVl).B)
          dut.io.completion.bits.cause.expect(if (error) EvuFault.Bus else EvuFault.None)
          if (store) {
            // Architectural completion must wait even for a delayed final Ack.
            pending shouldBe empty
            response shouldBe None
          }
          for (_ <- 0 until 5) {
            dut.io.completion.valid.expect(true.B)
            dut.io.completion.bits.jobId.expect(job.U)
            tick()
          }
          dut.io.completion.ready.poke(true.B)
          tick()
          dut.io.completion.ready.poke(false.B)
          for (_ <- 0 until 4) tick()
          waited = 0
          while ((pending.nonEmpty || response.nonEmpty) && waited < 1500) {
            tick()
            waited += 1
          }
          pending shouldBe empty
          response shouldBe None
        }

        val chunks = ep.vlenBytes / ep.mlenBytes
        chunks shouldBe 16
        // Three vectors advance the 32-entry load queue through tags 0..31 and back to 0.
        for (round <- 0 until 3) {
          val src = 0x1000 + round * 0x400
          val dst = 0x4000 + round * 0x400
          val reg = round + 1
          val values = Seq.tabulate(chunks) { chunk =>
            (0 until ep.mlenBytes / 4).map { element =>
              BigInt(0x10000000L + round * 0x10000 + chunk * 0x100 + element) << (element * 32)
            }.reduce(_ | _)
          }
          for (chunk <- values.indices) memory(BigInt(src + chunk * ep.mlenBytes)) = values(chunk)
          accesses.clear()
          issue(store = false, reg = reg, base = src, job = round * 2, sew = round)
          accesses.toSeq shouldBe values.indices.map(chunk => (BigInt(src + chunk * ep.mlenBytes), false))
          for (chunk <- values.indices) dut.io.vrf(reg * chunks + chunk).expect(values(chunk).U)
          accesses.clear()
          issue(store = true, reg = reg, base = dst, job = round * 2 + 1, sew = round)
          accesses.toSeq shouldBe values.indices.map(chunk => (BigInt(dst + chunk * ep.mlenBytes), true))
          for (chunk <- values.indices) memory(BigInt(dst + chunk * ep.mlenBytes)) shouldBe values(chunk)
        }
        issue(store = true, reg = 1, base = 0x6000, job = 6, sew = 0, error = true)
        issue(store = false, reg = 1, base = 0x1000, job = 7, sew = 0)
        accesses.clear()
        issue(store = false, reg = 1, base = 0x1000, job = 0, sew = 0, zeroVl = true)
        issue(store = true, reg = 1, base = 0x6000, job = 1, sew = 0, zeroVl = true)
        accesses shouldBe empty
      }
  }
}
