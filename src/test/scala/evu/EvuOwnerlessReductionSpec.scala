package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common._

class EvuOwnerlessReductionSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Ownerless single-EG min/max and mirrored reduction seeds"

  private def clear(data: Data): Unit = data match {
    case value: Bool => value.poke(false.B)
    case value: UInt => value.poke(0.U)
    case value: SInt => value.poke(0.S)
    case values: Vec[_] => values.foreach(clear)
    case bundle: Bundle => bundle.elements.values.foreach(clear)
  }
  private def raw(value: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(value).toLong & 0xffffffffL)

  it should "finish min/max while both sum owners wait for load data, using mirrored seeds on both paths" in {
    implicit val p: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
      case MonitorsEnabled => false
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }, 512, 64, 64, VectorParams.lgvParams.copy(debugVrf = true, reductionContexts = 2,
      interleaveReductions = true, fp32MinMaxTree = true, singleGroupMinMax = true,
      issStructure = VectorIssueStructure.SplitFP, vlissqEntries = 4, vpissqEntries = 4, vdqEntries = 4))
    test(LazyModule(new EvuInterleavedBackendShell(allowMinMax = true)).module)
      .withAnnotations(Seq(if (sys.env.get("EVU_TEST_VERILATOR").contains("1"))
        VerilatorBackendAnnotation else TreadleBackendAnnotation)) { dut =>
        clear(dut.io.instruction.bits)
        dut.io.instruction.valid.poke(false.B)
        dut.io.load.valid.poke(false.B)
        dut.io.load.bits.poke(0.U)
        dut.clock.step(270)
        val completed = scala.collection.mutable.Set.empty[Int]
        def tick(): Unit = {
          for (port <- dut.io.release.indices if dut.io.release(port).valid.peek().litToBoolean) {
            completed += dut.io.release(port).bits.peek().litValue.toInt
            dut.io.flags(port).expect(0.U)
          }
          dut.clock.step()
        }
        def send(bits: BigInt, vat: Int, length: Int): Unit = {
          clear(dut.io.instruction.bits)
          dut.io.instruction.bits.bits.poke(bits.U)
          dut.io.instruction.bits.vconfig.vl.poke(length.U)
          dut.io.instruction.bits.vconfig.vtype.vsew.poke(2.U)
          dut.io.instruction.bits.vat.poke(vat.U)
          dut.io.instruction.valid.poke(true.B)
          var waited = 0
          while (!dut.io.instruction.ready.peek().litToBoolean && waited < 200) { tick(); waited += 1 }
          dut.io.instruction.ready.expect(true.B)
          tick()
          dut.io.instruction.valid.poke(false.B)
        }
        def load(reg: Int, vat: Int, length: Int): Unit =
          send((BigInt(1) << 25) | (6 << 12) | (reg << 7) | 7, vat, length)
        def respond(value: Float, groups: Int): Unit = {
          dut.io.load.bits.poke((raw(value) | (raw(value) << 32)).U)
          dut.io.load.valid.poke(true.B)
          var accepted = 0
          var cycles = 0
          while (accepted < groups && cycles < 500) {
            if (dut.io.load.ready.peek().litToBoolean) accepted += 1
            tick()
            cycles += 1
          }
          accepted shouldBe groups
          dut.io.load.valid.poke(false.B)
        }
        def await(vats: Set[Int]): Unit = {
          var cycles = 0
          while (!vats.subsetOf(completed.toSet) && cycles < 500) { tick(); cycles += 1 }
          vats.subsetOf(completed.toSet) shouldBe true
        }
        def reduce(function: BigInt, destination: Int, seed: Int, source: Int, vat: Int, length: Int): Unit =
          send((function << 26) | (BigInt(1) << 25) | (source << 20) | (seed << 15) |
            (1 << 12) | (destination << 7) | 0x57, vat, length)

        load(3, 1, 2)
        respond(3f, 1)
        await(Set(1))
        load(5, 2, 1)
        respond(5f, 1)
        await(Set(2))
        // Both sums allocate an owner, but their source load has not returned.
        load(1, 3, 16)
        reduce(OPFFunct6.fredusum.litValue, 8, 0, 1, 4, 16)
        reduce(OPFFunct6.fredusum.litValue, 9, 0, 1, 5, 16)
        for (_ <- 0 until 30) tick()
        dut.io.perf.fmaIssue.expect(0.U)
        completed should not contain 4
        completed should not contain 5
        reduce(OPFFunct6.fredmax.litValue, 10, 5, 3, 6, 2)
        reduce(OPFFunct6.fredmin.litValue, 11, 5, 3, 7, 2)
        await(Set(6, 7))
        completed should not contain 4
        completed should not contain 5
        (dut.io.vrf(10 * 8).peek().litValue & 0xffffffffL) shouldBe raw(5f)
        (dut.io.vrf(11 * 8).peek().litValue & 0xffffffffL) shouldBe raw(3f)
        // Two SpecialSequencer seeds and two direct-CMP seeds all hit E0 mirrors.
        dut.io.vrfPerf.scalarForwards.expect(4.U)
        respond(2f, 8)
        await(Set(3, 4, 5))
        (dut.io.vrf(8 * 8).peek().litValue & 0xffffffffL) shouldBe raw(32f)
        (dut.io.vrf(9 * 8).peek().litValue & 0xffffffffL) shouldBe raw(32f)
      }
  }
}
