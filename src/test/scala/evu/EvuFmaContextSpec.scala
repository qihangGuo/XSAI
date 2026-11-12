package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.ExecuteContextLayout
import xiangshan.evu.common._

class EvuFmaContextSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Ordinary FMA contexts sharing one arithmetic path"

  it should "keep both reduction owner IDs stable with one or two ordinary contexts" in {
    for (ordinary <- 1 to 2; reductions <- 2 to 4) {
      val fma = ExecuteContextLayout("fp_fma", true, ordinary, reductions)
      (0 until ordinary).foreach(i => fma.reductionOwner(i) shouldBe None)
      (0 until reductions).foreach(i => fma.reductionOwner(ordinary + i) shouldBe Some(i))
      val cmp = ExecuteContextLayout("fp_cmp", true, ordinary, reductions)
      cmp.size shouldBe 1 + reductions
      cmp.reductionOwner(1) shouldBe Some(0)
      ExecuteContextLayout("fp_exp", true, ordinary, reductions).size shouldBe 1
      ExecuteContextLayout("fp_fma", false, ordinary, reductions).size shouldBe 1
    }
  }

  it should "execute a younger independent add while an older add waits for a load and retain dependencies" in {
    assume(sys.env.get("EVU_FULL_BACKEND_TREADLE").contains("1"),
      "Full production backend test is server-only; set EVU_FULL_BACKEND_TREADLE=1")
    val base = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    implicit val p: Parameters = EvuVectorParameters(base, 512, 64, 64,
      VectorParams.lgvParams.copy(debugVrf = true, diagnosticCounters = true,
        reductionContexts = 2, fmaOrdinaryContexts = 2, interleaveReductions = true,
        issStructure = VectorIssueStructure.SplitFP, vlissqEntries = 4, vpissqEntries = 4,
        vdqEntries = 4, vrfBanking = 8))
    test(LazyModule(new EvuInterleavedBackendShell).module)
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        def clear(x: Data): Unit = x match {
          case b: Bool => b.poke(false.B)
          case u: UInt => u.poke(0.U)
          case s: SInt => s.poke(0.S)
          case v: Vec[_] => v.foreach(clear)
          case b: Bundle => b.elements.values.foreach(clear)
        }
        def raw(x: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(x).toLong & 0xffffffffL)
        clear(dut.io.instruction.bits)
        dut.io.instruction.valid.poke(false.B)
        dut.io.load.valid.poke(false.B)
        dut.io.load.bits.poke(0.U)
        dut.clock.step(270)
        val done = scala.collection.mutable.Set.empty[Int]
        def tick(): Unit = {
          for (r <- dut.io.release.indices if dut.io.release(r).valid.peek().litToBoolean) {
            done += dut.io.release(r).bits.peek().litValue.toInt
            dut.io.flags(r).expect(0.U)
          }
          dut.clock.step()
        }
        def until(condition: => Boolean): Unit = {
          var cycles = 0
          while (!condition && cycles < 1200) { tick(); cycles += 1 }
          condition shouldBe true
        }
        def send(bits: BigInt, vat: Int, vl: Int = 16): Unit = {
          clear(dut.io.instruction.bits)
          dut.io.instruction.bits.bits.poke(bits.U)
          dut.io.instruction.bits.vconfig.vl.poke(vl.U)
          dut.io.instruction.bits.vconfig.vtype.vsew.poke(2.U)
          dut.io.instruction.bits.vat.poke(vat.U)
          dut.io.instruction.valid.poke(true.B)
          until(dut.io.instruction.ready.peek().litToBoolean)
          tick()
          dut.io.instruction.valid.poke(false.B)
        }
        def load(reg: Int, vat: Int): Unit =
          send((BigInt(1) << 25) | (6 << 12) | (reg << 7) | 7, vat)
        def supply(value: Float): Unit = {
          dut.io.load.bits.poke((raw(value) | (raw(value) << 32)).U)
          dut.io.load.valid.poke(true.B)
          var accepted = 0
          while (accepted < 8) {
            until(dut.io.load.ready.peek().litToBoolean)
            tick(); accepted += 1
          }
          dut.io.load.valid.poke(false.B)
        }
        def fp(f6: BigInt, reg: Int, a: Int, b: Int, vat: Int, vl: Int = 16): Unit =
          send((f6 << 26) | (BigInt(1) << 25) | (b << 20) | (a << 15) |
            (1 << 12) | (reg << 7) | 0x57, vat, vl)
        for ((reg, value) <- Seq((0, 0f), (1, 1f), (2, 2f))) {
          load(reg, reg); supply(value); until(done(reg))
        }
        val add = OPFFunct6.fadd.litValue
        load(8, 3) // Do not supply this load until the independent add completes.
        fp(add, 9, 8, 1, 4)
        fp(add, 10, 1, 2, 5)
        until(done(5))
        done should not contain 4
        (dut.io.vrf(10 * 8).peek().litValue & 0xffffffffL) shouldBe raw(3f)
        supply(5f); until(done(4))
        (dut.io.vrf(9 * 8).peek().litValue & 0xffffffffL) shouldBe raw(6f)
        fp(add, 11, 1, 2, 6)
        fp(add, 11, 11, 1, 7) // RAW and WAW.
        fp(add, 12, 11, 2, 8)
        fp(add, 11, 2, 2, 9) // WAR against the older consumer of v11.
        fp(OPFFunct6.fredusum.litValue, 13, 0, 11, 10)
        fp(OPFFunct6.fredusum.litValue, 14, 0, 12, 11)
        fp(add, 15, 13, 14, 12, vl = 1)
        until(done(12))
        done should contain allOf (6, 7, 8, 9, 10, 11, 12)
        for ((reg, value) <- Seq((11, 4f), (12, 6f), (13, 64f), (14, 96f), (15, 160f))) {
          (dut.io.vrf(reg * 8).peek().litValue & 0xffffffffL) shouldBe raw(value)
        }
        dut.io.perf.byOp(EvuOpClass.names.indexOf("red_sum")).completed.expect(2.U)
        dut.io.perf.byOp(EvuOpClass.names.indexOf("e0_addsub")).completed.expect(1.U)
        dut.io.busy.expect(false.B)
      }
  }
}
