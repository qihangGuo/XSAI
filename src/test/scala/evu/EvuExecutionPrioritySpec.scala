package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.backend.SharedExecutionArbiter
import xiangshan.evu.common.VectorParams

class EvuExecutionPrioritySpec extends AnyFlatSpec with ChiselScalatestTester {
  private def parameters: Parameters = EvuVectorParameters(Parameters.empty.alterPartial {
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }, 512, 256, 128, VectorParams.lgvParams)

  private def clear(data: Data): Unit = data match {
    case b: Bool => b.poke(false.B)
    case u: UInt => u.poke(0.U)
    case s: SInt => s.poke(0.S)
    case v: Vec[_] => v.foreach(clear)
    case b: Bundle => b.elements.values.foreach(clear)
  }

  behavior of "EVU bounded critical-result priority"

  it should "prioritize E0 and reduction finishing work without consuming budget under backpressure" in {
    implicit val p: Parameters = parameters
    test(new SharedExecutionArbiter(3, 4, 1, prioritizeCritical = true))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        for (i <- 0 until 3) {
          clear(dut.io.in(i).bits)
          dut.io.in(i).valid.poke(true.B)
          dut.io.in(i).bits.vat.poke(i.U)
          dut.io.in(i).bits.funct3.poke(1.U)
          dut.io.in(i).bits.vd_eew.poke(2.U)
          dut.io.in(i).bits.eidx_mask.poke(15.U)
        }
        dut.io.in(1).bits.head.poke(true.B)
        dut.io.in(1).bits.tail.poke(true.B)
        // Effective E0 is classified independently of the configured VL.
        dut.io.in(1).bits.vl.poke(128.U)
        dut.io.in(2).bits.acc.poke(true.B)
        dut.io.in(2).bits.acc_fold.poke(true.B)
        dut.io.out.ready.poke(false.B)
        dut.io.in.foreach(_.ready.expect(false.B))
        dut.io.priorityIssue.expect(false.B)
        dut.clock.step(6)
        dut.io.out.ready.poke(true.B)
        val urgentWinners = scala.collection.mutable.Set.empty[Int]
        for (_ <- 0 until 3) {
          val winner = dut.io.out.bits.vat.peek().litValue.toInt
          assert(winner == 1 || winner == 2)
          urgentWinners += winner
          dut.io.priorityIssue.expect(true.B)
          dut.io.contended.expect(true.B)
          dut.clock.step()
        }
        assert(urgentWinners == Set(1, 2))
        dut.io.out.bits.vat.expect(0.U)
        dut.io.priorityIssue.expect(false.B)
        dut.clock.step()
        assert(dut.io.out.bits.vat.peek().litValue != 0)
      }
  }

  it should "remain fair among multiple ordinary contexts during sustained priority traffic" in {
    implicit val p: Parameters = parameters
    test(new SharedExecutionArbiter(4, 4, 1, prioritizeCritical = true))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        for (i <- 0 until 4) {
          clear(dut.io.in(i).bits)
          dut.io.in(i).valid.poke(true.B)
          dut.io.in(i).bits.vat.poke(i.U)
          dut.io.in(i).bits.funct3.poke(1.U)
          dut.io.in(i).bits.acc.poke((i >= 2).B)
          dut.io.in(i).bits.acc_merge.poke((i >= 2).B)
        }
        dut.io.out.ready.poke(true.B)
        val regular = scala.collection.mutable.ArrayBuffer.empty[Int]
        var burst = 0
        for (_ <- 0 until 32) {
          val winner = dut.io.out.bits.vat.peek().litValue.toInt
          if (winner < 2) {
            regular += winner
            burst = 0
          } else {
            burst += 1
            assert(burst <= 3)
          }
          dut.clock.step()
        }
        assert(regular.size == 8)
        assert(regular.sliding(2).forall(pair => pair(0) != pair(1)))
      }
  }

  it should "keep future result slots protected even for a critical candidate" in {
    implicit val p: Parameters = parameters
    test(new SharedExecutionArbiter(2, 4, 1, prioritizeCritical = true))
      .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        for (i <- 0 until 2) {
          clear(dut.io.in(i).bits)
          dut.io.in(i).bits.vat.poke(i.U)
          dut.io.in(i).bits.funct3.poke(1.U)
          dut.io.in(i).valid.poke((i == 0).B)
        }
        dut.io.out.ready.poke(true.B)
        dut.io.in(0).bits.pipe_depth.poke(3.U)
        dut.io.out.bits.vat.expect(0.U)
        dut.clock.step()
        dut.io.in(0).bits.pipe_depth.poke(0.U)
        dut.io.in(1).valid.poke(true.B)
        dut.io.in(1).bits.acc.poke(true.B)
        dut.io.in(1).bits.acc_fold.poke(true.B)
        for (depth <- Seq(2, 1)) {
          dut.io.in(1).bits.pipe_depth.poke(depth.U)
          dut.io.available(1).expect(false.B)
          dut.io.in(1).ready.expect(false.B)
          dut.io.out.bits.vat.expect(0.U)
          dut.clock.step()
        }
        dut.io.in(1).bits.pipe_depth.poke(0.U)
        dut.io.out.valid.expect(false.B)
        dut.clock.step()
        dut.io.available(1).expect(true.B)
        dut.io.out.bits.vat.expect(1.U)
        dut.io.priorityIssue.expect(true.B)
      }
  }
}
