package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.TileVisibilityNodeKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.{EvuParams, adapter}
import xiangshan.evu.area.{EvuAreaMain, EvuSynthesisConfig}
import xiangshan.evu.backend.{PackDescriptorLine, SecondDispatch}
import xiangshan.evu.common.{DispatchQueue, EvuSumLookahead, VectorIssueInst, VXIssuePathParams}

class SecondDispatchTestShell(val paths: Seq[VXIssuePathParams])(implicit p: Parameters) extends LazyModule {
  val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("dispatch-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v1(
    address = Seq(AddressSet(0, 0xffffffffL)), supportsGet = TransferSizes(1, 64))), beatBytes = 64)))
  sink := client
  lazy val module = new SecondDispatchTestShellImp(this)
}

class SecondDispatchTestShellImp(outer: SecondDispatchTestShell)(implicit p: Parameters)
    extends LazyModuleImp(outer) {
  val choice = Module(new SecondDispatch(outer.paths)(p.alterPartial { case TileVisibilityNodeKey => outer.client }))
  val io = IO(chiselTypeOf(choice.io))
  io <> choice.io
}

class EvuDispatchReuseSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "keep the head across second dequeues, simultaneous enqueues and ring wrap" in {
    implicit val p: Parameters = Parameters.empty
    test(new DispatchQueue(UInt(16.W), 4)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val model = scala.collection.mutable.ArrayBuffer.empty[Int]
      val random = new scala.util.Random(12287)
      for (id <- 0 until 400) {
        val push = random.nextBoolean()
        val pop = random.nextBoolean()
        val second = model.size >= 2 && random.nextBoolean()
        dut.io.enq.valid.poke(push.B)
        dut.io.enq.bits.poke(id.U)
        dut.io.deq.ready.poke(pop.B)
        dut.takeSecond.poke(second.B)
        dut.io.count.expect(model.size.U)
        dut.io.enq.ready.expect((model.size < 4).B)
        dut.io.deq.valid.expect(model.nonEmpty.B)
        model.headOption.foreach(h => dut.io.deq.bits.expect(h.U))
        dut.second.valid.expect((model.size >= 2).B)
        model.lift(1).foreach(n => dut.second.bits.expect(n.U))
        val enq = push && model.size < 4
        if (pop && model.nonEmpty) model.remove(if (second) 1 else 0)
        if (enq) model += id
        dut.clock.step()
      }
    }
  }

  it should "only cross blocked independent ordinary instructions on different issue paths" in {
    val ep = EvuParams(enable = true)
    val vp = EvuSynthesisConfig.vectorParams(ep).copy(diagnosticCounters = true)
    implicit val p: Parameters = adapter.EvuVectorParameters(EvuAreaMain.parameters,
      ep.vlenBits, ep.dlenBits, ep.mlenBits, vp)
    val paths = vp.issStructure.generate(vp)
    test(LazyModule(new SecondDispatchTestShell(paths)).module).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val fma = paths.indexWhere(_.name == "fp_fma")
      val cmp = paths.indexWhere(_.name == "fp_cmp")
      def inst(i: VectorIssueInst, f6: Int, f3: Int, rd: Int, rs1: Int, rs2: Int,
          vm: Int = 1, opcode: Int = 0x57): Unit = {
        i.bits.poke(((BigInt(f6) << 26) | (vm << 25) | (rs2 << 20) |
          (rs1 << 15) | (f3 << 12) | (rd << 7) | opcode).U)
        i.vconfig.vtype.vsew.poke(2.U)
        i.vconfig.vtype.vlmul_mag.poke(0.U)
        i.vconfig.vtype.vlmul_sign.poke(false.B)
        i.vconfig.vtype.vill.poke(false.B)
        i.vconfig.vl.poke(16.U)
        i.vstart.poke(0.U)
        i.emul.poke(0.U)
        i.fission_vl.valid.poke(false.B)
      }
      val a = dut.io.head
      val b = dut.io.second.bits
      val probe = dut.io.sumProbe.get
      probe.onlyOwnerMissing.poke(false.B); probe.dequeue.poke(false.B)
      dut.io.second.valid.poke(true.B)
      dut.io.ready.foreach(_.poke(false.B))
      dut.io.ready(cmp).poke(true.B)
      inst(a, 0x3f, 0, 4, 12, 6) // BLOCK_PACK reads v12 and v6, writes v4
      inst(b, 0x3e, 0, 1, 0, 2)  // BLOCK_AMAX only reads vs2
      dut.io.take.expect(true.B)
      dut.io.ready(fma).poke(true.B)
      dut.io.take.expect(false.B)
      dut.io.ready(fma).poke(false.B)
      for ((rd, rs2) <- Seq((1, 4), (4, 2), (12, 2), (6, 2))) {
        inst(b, 0x3e, 0, rd, 0, rs2)
        dut.io.take.expect(false.B)
      }
      inst(b, 8, 1, 1, 4, 2) // sign injection reads both sources
      dut.io.take.expect(false.B)
      inst(b, 0x3e, 0, 1, 0, 2)
      for ((f6, f3, vm, op) <- Seq((1, 1, 1, 0x57), (0x3f, 0, 0, 0x57),
          (0x3f, 0, 1, 0x07), (0x3f, 0, 1, 0x27), (0, 7, 1, 0x57))) {
        inst(a, f6, f3, 4, 12, 6, vm, op)
        dut.io.take.expect(false.B)
      }
      inst(a, 0x3f, 0, 4, 12, 6)
      b.vconfig.vtype.vlmul_mag.poke(1.U)
      dut.io.take.expect(false.B)
      inst(b, 0x3f, 0, 1, 0, 2)
      dut.io.take.expect(false.B)

      // A shadow observation never dequeues the second entry, even on the same FMA path.
      def event(name: String, on: Boolean): Unit = probe.events(EvuSumLookahead.names.indexOf(name)).expect(on.B)
      inst(a, 1, 1, 4, 12, 6) // FREDUSUM v4, v6, v12
      inst(b, 0, 1, 1, 3, 2) // FADD
      dut.io.ready(fma).poke(true.B); event("owner_only", false)
      probe.onlyOwnerMissing.poke(true.B); dut.io.second.valid.poke(false.B); event("empty", true)
      dut.io.second.valid.poke(true.B); event("ordinary", true); event("eligible_cycles", true)
      event("eligible_commands", true); dut.io.take.expect(false.B); dut.clock.step()
      dut.io.ready(fma).poke(false.B); event("eligible_cycles", false); dut.clock.step()
      dut.io.ready(fma).poke(true.B); event("eligible_cycles", true); event("eligible_commands", false)
      probe.onlyOwnerMissing.poke(false.B); probe.dequeue.poke(true.B); dut.clock.step()
      probe.onlyOwnerMissing.poke(true.B); probe.dequeue.poke(false.B); event("eligible_commands", true)
      for ((d, s1, s2) <- Seq((4, 3, 2), (12, 3, 2), (6, 3, 2), (1, 4, 2), (1, 3, 4))) {
        inst(b, 0, 1, d, s1, s2); event("ordinary", true); event("eligible_cycles", false)
      }
      inst(b, 1, 1, 1, 0, 2); event("reduction", true); event("ordinary", false)
      inst(b, 1, 1, 1, 0, 2, opcode = 7); event("memory", true); event("reduction", false)
      inst(b, 0, 7, 1, 0, 2); event("other", true); event("eligible_cycles", false)
      inst(a, 1, 1, 4, 12, 6, vm = 0); event("owner_only", false)
    }
  }

  it should "share one full line and reject concurrent writes, stale fills and flushes" in {
    test(new PackDescriptorLine(8, 512, 2, 4)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val data = (BigInt(1) << 511) | 9
      dut.io.fill.foreach(_.valid.poke(false.B))
      dut.io.invalidate.foreach(_.valid.poke(false.B))
      def fill(i: Int, tag: Int, value: BigInt): Unit = {
        dut.io.fill(i).valid.poke(true.B)
        dut.io.fill(i).bits.eg.poke(tag.U)
        dut.io.fill(i).bits.data.poke(value.U)
      }
      fill(1, 248, data)
      dut.clock.step()
      dut.io.tag.valid.expect(true.B)
      dut.io.tag.bits.expect(248.U)
      dut.io.data.expect(data.U)
      dut.io.invalidate(0).valid.poke(true.B)
      dut.io.invalidate(0).bits.poke(248.U)
      dut.io.tag.valid.expect(false.B)
      dut.clock.step()
      dut.io.invalidate(0).valid.poke(false.B)
      dut.io.tag.valid.expect(false.B)
      fill(0, 224, 7)
      dut.clock.step()
      dut.io.tag.bits.expect(224.U)
      dut.io.data.expect(7.U)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.tag.valid.expect(false.B)
    }
  }
}
