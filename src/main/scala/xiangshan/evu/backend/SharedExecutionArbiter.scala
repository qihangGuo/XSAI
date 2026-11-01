package xiangshan.evu.backend

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile.CoreModule
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu.common._

/** Logical sequencers share a FU result channel, including mixed latencies. */
class SharedExecutionArbiter(contexts: Int, maxDepth: Int, nFUs: Int)(implicit p: Parameters)
    extends CoreModule {
  require(contexts > 0 && maxDepth > 0)
  private val depthBits = log2Ceil(maxDepth) max 1
  val io = IO(new Bundle {
    val in = Vec(contexts, Flipped(Decoupled(new ExecuteMicroOpWithData(nFUs))))
    val out = Decoupled(new ExecuteMicroOpWithData(nFUs))
    val available = Output(Vec(contexts, Bool()))
  })
  val arb = Module(new RRArbiter(new ExecuteMicroOpWithData(nFUs), contexts))
  arb.io.in <> io.in
  io.out <> arb.io.out
  val occupied = RegInit(0.U(maxDepth.W))
  val allocation = Mux(io.out.fire && !io.out.bits.iterative,
    UIntToOH(io.out.bits.pipe_depth, maxDepth), 0.U(maxDepth.W))
  occupied := (occupied | allocation) >> 1
  for (i <- 0 until contexts) {
    val depth = io.in(i).bits.pipe_depth
    io.available(i) := depth < maxDepth.U && !occupied(depth(depthBits - 1, 0))
  }
  when(io.out.fire && !io.out.bits.iterative) {
    assert(io.out.bits.pipe_depth < maxDepth.U &&
      !occupied(io.out.bits.pipe_depth(depthBits - 1, 0)), "shared FU result channel was double-booked")
  }
}
