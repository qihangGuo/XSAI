package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import xiangshan.evu.EvuPreflightReasons
import xiangshan.evu.common.{EvuIdleHazards, PipeScheduler}

class EvuStallDiagnosticsHarness extends Module {
  val io = IO(new Bundle {
    val completed = Input(Bool())
    val arithmetic = Input(Bool())
    val arithmeticOnly = Output(Bool())
    val contexts = Input(Vec(2, UInt(3.W)))
    val issue = Input(Bool())
    val buckets = Output(UInt(4.W))
    val unknown = Input(Bool())
    val hits = Input(UInt(2.W))
    val disjoint = Input(UInt(2.W))
    val allDisjoint = Output(Bool())
  })
  val status = RegInit(0.U(4.W))
  status := Cat(status(2, 0), io.completed)
  io.arithmeticOnly := EvuPreflightReasons.arithmeticOnly(status, io.completed, io.arithmetic)
  io.buckets := VecInit(EvuIdleHazards.buckets(io.contexts.toSeq, io.issue)).asUInt
  io.allDisjoint := EvuIdleHazards.onlyDisjoint(io.unknown, (0 until 2).map(i => (io.hits(i), io.disjoint(i))))
}

class EvuStallDiagnosticsSpec extends AnyFlatSpec with ChiselScalatestTester {
  it should "veto non-arithmetic and fault completions on arrival and throughout drain" in {
    test(new EvuStallDiagnosticsHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { d =>
      var history = List.empty[Int]
      // 0: no completion, 1: normal arithmetic, 2: memory/control/fault
      for (kind <- Seq(1, 1, 2, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 2, 0)) {
        d.io.completed.poke((kind != 0).B)
        d.io.arithmetic.poke((kind == 1).B)
        d.io.arithmeticOnly.expect((history.contains(1) && !history.contains(2) && kind != 2).B)
        d.clock.step()
        history = (kind :: history).take(4)
      }
    }
  }

  it should "count idle cycles once and reject unknown, overlapping or absent writers" in {
    test(new EvuStallDiagnosticsHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { d =>
      for (a <- 0 until 8; b <- 0 until 8; issue <- Seq(false, true)) {
        d.io.contexts(0).poke(a.U)
        d.io.contexts(1).poke(b.U)
        d.io.issue.poke(issue.B)
        val mask = a | b
        val expected = if (issue || mask == 0) 0 else if (Integer.bitCount(mask) > 1) 8 else mask
        d.io.buckets.expect(expected.U)
      }
      for (hits <- 0 until 4; disjoint <- 0 until 4; unknown <- Seq(false, true)) {
        d.io.hits.poke(hits.U)
        d.io.disjoint.poke(disjoint.U)
        d.io.unknown.poke(unknown.B)
        d.io.allDisjoint.expect((!unknown && hits != 0 && (hits & ~disjoint) == 0).B)
      }
    }
  }

  it should "observe a missing reservation without making a real request" in {
    test(new PipeScheduler(2, 4, observe = true)).withAnnotations(Seq(TreadleBackendAnnotation)) { d =>
      val first = d.io.reqs(0)
      val second = d.io.reqs(1)
      second.request.poke(false.B)
      second.fire.poke(false.B)
      second.depth.poke(2.U)
      first.depth.poke(2.U)
      first.request.poke(true.B)
      first.fire.poke(true.B)
      d.wouldGrant.get(1).expect(false.B)
      second.available.expect(true.B)
      d.clock.step()
      first.request.poke(false.B)
      first.fire.poke(false.B)
      second.depth.poke(1.U)
      d.wouldGrant.get(1).expect(false.B)
      second.depth.poke(2.U)
      d.wouldGrant.get(1).expect(true.B)
      d.clock.step(3)
      second.depth.poke(1.U)
      d.wouldGrant.get(1).expect(true.B)
    }
  }
}
