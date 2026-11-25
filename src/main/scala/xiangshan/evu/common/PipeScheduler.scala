package xiangshan.evu.common

import chisel3._
import chisel3.util._

class PipeScheduler(nReqs: Int, maxDepth: Int, observe: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val reqs = Vec(nReqs, new Bundle {
      val request = Input(Bool())
      val available = Output(Bool())
      val fire = Input(Bool())
      val depth = Input(UInt(log2Ceil(maxDepth).W))
    })
  })

  val tracker = RegInit(0.U(maxDepth.W))
  // Unlike available, the probe also checks clients that did not request.
  val wouldGrant = Option.when(observe)(IO(Output(Vec(nReqs, Bool()))))
  wouldGrant.foreach(_.foreach(_ := true.B))

  val ohs = io.reqs.map(r => UIntToOH(r.depth))
  io.reqs.foreach(_.available := true.B)

  for (d <- 0 until maxDepth) {
    var allocated = tracker(d)
    for (i <- 0 until nReqs) {
      val available = !allocated
      wouldGrant.foreach(g => when(ohs(i)(d) && !available) { g(i) := false.B })
      val active = io.reqs(i).request && ohs(i)(d)
      when (!available && active) { io.reqs(i).available := false.B }
      allocated = allocated || active
    }
  }
  val allocs = io.reqs.zip(ohs).map { case (r,oh) => Mux(r.fire && r.request, oh, 0.U) }.reduce(_|_)
  when (tracker =/= 0.U || allocs =/= 0.U) {
    tracker := (tracker | allocs) >> 1
  }
}
