package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.TileVisibilityNodeKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common._
import xiangshan.evu.frontend.VectorDispatcher
import xiangshan.evu.mem.AddrGen

/** Exercise the CPU command link and Saturn address generation without FP units. */
class EvuSaturnAddressIO(ep: EvuParams) extends Bundle {
    val command = Flipped(Decoupled(new EvuCoreCommand(ep)))
    val request = Decoupled(new Bundle {
      val vaddr = UInt(64.W)
      val paddr = UInt(64.W)
      val mask = UInt(ep.mlenBytes.W)
      val store = Bool()
      val legal = Bool()
    })
}

class EvuSaturnAddressShell(val ep: EvuParams)(implicit p: Parameters) extends LazyModule {
  val visibility = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("addr-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(
    Seq(TLSlaveParameters.v1(address = Seq(AddressSet(0, 0xffffffffL)),
      supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := visibility
  lazy val module = new EvuSaturnAddressShellImp(this)
}

class EvuSaturnAddressShellImp(outer: EvuSaturnAddressShell)(implicit p: Parameters) extends LazyModuleImp(outer) {
  val io = IO(new EvuSaturnAddressIO(outer.ep))
  val harness = Module(new EvuSaturnAddressHarness(outer.ep)(p.alterPartial {
    case TileVisibilityNodeKey => outer.visibility
  }))
  io <> harness.io
  val bus = outer.visibility.out.head._1
  bus.a.valid := false.B
  bus.a.bits := 0.U.asTypeOf(bus.a.bits)
  bus.d.ready := true.B
  val manager = outer.sink.in.head._1
  manager.a.ready := true.B
  manager.d.valid := false.B
  manager.d.bits := 0.U.asTypeOf(manager.d.bits)
}

class EvuSaturnAddressHarness(ep: EvuParams)(implicit p: Parameters) extends Module {
  val io = IO(new EvuSaturnAddressIO(ep))
  val adapter = Module(new EvuDispatchAdapter(ep))
  adapter.io.in.valid := io.command.valid
  io.command.ready := adapter.io.in.ready
  adapter.io.in.bits := EvuDispatchRequest.fromCore(io.command.bits, io.command.fire, ep)
  adapter.io.flush := false.B
  val cmd = adapter.io.out
  val dispatch = Module(new VectorDispatcher)
  val ops = Module(new Queue(new VectorMemMacroOp, 1))
  val addresses = Module(new AddrGen)
  val translate = Module(new EvuPageTranslate(ep))
  val pageMap = Reg(new EvuPageMap(ep))
  when(cmd.fire) { pageMap := cmd.bits.pageMap }
  dispatch.io.issue.valid := cmd.valid
  cmd.ready := dispatch.io.issue.ready
  dispatch.io.issue.bits := 0.U.asTypeOf(dispatch.io.issue.bits)
  dispatch.io.issue.bits.bits := EvuSaturnMemory.encode(cmd.bits)
  dispatch.io.issue.bits.rs1_data := cmd.bits.base
  dispatch.io.issue.bits.vconfig.vl := cmd.bits.vl
  dispatch.io.issue.bits.vconfig.vtype.vsew := cmd.bits.sew
  dispatch.io.issue.bits.mop := VectorConsts.mopUnit
  dispatch.io.dis.ready := true.B
  dispatch.io.scalar_resp.ready := true.B
  dispatch.io.vat_release.foreach { r => r.valid := false.B; r.bits := 0.U }
  ops.io.enq <> dispatch.io.mem
  addresses.io.valid := ops.io.deq.valid
  addresses.io.op := ops.io.deq.bits
  ops.io.deq.ready := addresses.io.done
  addresses.io.lsiq_id := 0.U
  addresses.io.tag.valid := true.B
  addresses.io.tag.bits := 0.U
  addresses.io.maskindex.index := 0.U
  addresses.io.maskindex.mask := true.B
  addresses.io.maskindex.valid := true.B
  addresses.io.out.ready := true.B
  val va = (pageMap.base & ~4095.U(64.W)) +
    (addresses.io.req.bits.addr & ~(ep.mlenBytes - 1).U(64.W))
  translate.io.pageMap := pageMap
  translate.io.vaddr := va & ~63.U(64.W)
  io.request.valid := addresses.io.req.valid
  addresses.io.req.ready := io.request.ready
  io.request.bits.vaddr := va
  io.request.bits.paddr := translate.io.paddr | va(5, 0)
  io.request.bits.mask := addresses.io.req.bits.mask
  io.request.bits.store := ops.io.deq.bits.store
  io.request.bits.legal := translate.io.legal
}

class EvuSaturnAddressSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Saturn virtual address generation"

  it should "decode real LOAD/STORE encodings and retain the carry and byte masks across pages" in {
    val ep = EvuParams(enable = true, vlenBits = 512,
      dlenBits = 256, mlenBits = 256, lanes = 4, vrfBanks = 8)
    val baseParams = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    implicit val p: Parameters = EvuVectorParameters(baseParams, ep.vlenBits, ep.dlenBits, ep.mlenBits)
    test(LazyModule(new EvuSaturnAddressShell(ep)).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.command.valid.poke(false.B)
      dut.io.request.ready.poke(false.B)
      val cases = for (store <- Seq(false, true); sew <- 0 to 2;
        partial <- (if (sew == 2) Seq(false, true) else Seq(false))) yield (store, sew, partial)
      for ((store, sew, partial) <- cases) {
        val base = BigInt("ffffffc040000fe0", 16) + (if (partial) 28 else 0)
        val f7 = if (store) 0x11 else 0x10
        dut.io.command.bits.instr.poke(((BigInt(f7) << 25) | (sew << 12) | (3 << 7) | 0x0b).U)
        dut.io.command.bits.length.poke(ep.vlenBytes.U)
        dut.io.command.bits.aux.poke(0.U)
        dut.io.command.bits.vl.poke((64 >> sew).U)
        dut.io.command.bits.base.poke(base.U)
        dut.io.command.bits.pageMap.enabled.poke(true.B)
        dut.io.command.bits.pageMap.base.poke(base.U)
        dut.io.command.bits.pageMap.length.poke(64.U)
        dut.io.command.bits.pageMap.ppn(0).poke(3.U)
        dut.io.command.bits.pageMap.ppn(1).poke(9.U)
        dut.io.command.valid.poke(true.B)
        dut.io.command.ready.expect(true.B)
        dut.clock.step()
        dut.io.command.valid.poke(false.B)
        val requests = if (partial) Seq((0, 0x3fe0, BigInt("f0000000", 16)),
          (32, 0x9000, BigInt("ffffffff", 16)), (64, 0x9020, BigInt("0fffffff", 16)))
        else Seq((0, 0x3fe0, BigInt("ffffffff", 16)), (32, 0x9000, BigInt("ffffffff", 16)))
        for ((offset, pa, mask) <- requests) {
          var cycles = 0
          while (!dut.io.request.valid.peek().litToBoolean && cycles < 20) {
            dut.clock.step(); cycles += 1
          }
          dut.io.request.valid.expect(true.B)
          dut.io.request.bits.vaddr.expect(((base & ~BigInt(31)) + offset).U)
          dut.io.request.bits.paddr.expect(pa.U)
          dut.io.request.bits.mask.expect(mask.U)
          dut.io.request.bits.store.expect(store.B)
          dut.io.request.bits.legal.expect(true.B)
          dut.clock.step(2)
          dut.io.request.bits.paddr.expect(pa.U)
          dut.io.request.ready.poke(true.B)
          dut.clock.step()
          dut.io.request.ready.poke(false.B)
        }
        dut.io.request.valid.expect(false.B)
      }
    }
  }
}
