package xiangshan.evu.adapter

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tile.{CoreModule, FPResult}
import xiangshan.evu.backend.VectorBackend
import xiangshan.evu.common._
import xiangshan.evu.frontend.VectorDispatcher
import xiangshan.evu.mem.{VectorMemIO, VectorMemUnit}

/**
  * XSAI-facing Saturn backend without the Rocket-specific frontend or TL shell.
  *
  * Address translation, job/epoch recovery and TileLink source allocation stay
  * in the EVU integration layer. This module owns Saturn's VAT, issue queues,
  * sequencers, banked VRF, chaining and vector memory datapath.
  */
class EvuVectorSubsystem(implicit p: Parameters)
    extends CoreModule()(p)
    with HasVectorParams {
  val io = IO(new Bundle {
    val issue = Flipped(Decoupled(new VectorIssueInst))
    val dmem = new VectorMemIO
    val scalarResp = Decoupled(new ScalarWrite)
    val completed = Output(Vec(nRelease, Valid(UInt(vParams.vatSz.W))))
    val completionVxsat = Output(Vec(nRelease, Bool()))
    val completionFflags = Output(Vec(nRelease, UInt(5.W)))
    val setVxsat = Output(Bool())
    val setFflags = Output(Valid(UInt(5.W)))
    val busy = Output(Bool())
    val debugVrf = Option.when(vParams.debugVrf)(Output(Vec(egsTotal, UInt(dLen.W))))
    // The special sequencer is the requester; its VRF requests enter this
    // subsystem and the read responses leave it. Mark the
    // complete nested bundle as Flipped so backend.io.special has real
    // drivers instead of an unbound req/bits connection.
    val special = Flipped(new Bundle {
      val read = Vec(3, new VectorReadIO)
    })
  })

  private val dispatcher = Module(new VectorDispatcher)
  private val backend = Module(new VectorBackend)
  private val vmu = Module(new VectorMemUnit(None))
  private val scalarArbiter = Module(new Arbiter(new ScalarWrite, 2))

  dispatcher.io.issue <> io.issue
  io.debugVrf.foreach(_ := backend.io.debugVrf.get)
  backend.io.special <> io.special
  backend.io.dis <> dispatcher.io.dis
  vmu.io.enq <> dispatcher.io.mem

  backend.io.vat_head := dispatcher.io.vat_head
  backend.io.vat_tail := dispatcher.io.vat_tail
  dispatcher.io.vat_release := backend.io.vat_release
  io.completed := backend.io.vat_release
  io.completionVxsat := backend.io.completion_vxsat
  io.completionVxsat(1) := false.B
  io.completionFflags := backend.io.completion_fflags
  // Slot 1 is the store-data VAT release. It remains available internally
  // for scheduling, but an EVU Store completes only after the VMU drains it.
  io.completed(1) := vmu.io.storeCompleted
  io.completionFflags(1) := 0.U

  backend.io.vmu <> vmu.io.vu
  io.dmem <> vmu.io.dmem

  // Indexed/scatter-gather translation is deliberately outside the first
  // HBL2 integration. Unit-stride and strided accesses use the VMU directly.
  backend.io.index_access.valid := false.B
  backend.io.index_access.vrs := 0.U
  backend.io.index_access.eidx := 0.U
  backend.io.index_access.eew := 0.U
  backend.io.mask_access.valid := false.B
  backend.io.mask_access.eidx := 0.U

  vmu.io.scalar_check.addr := 0.U
  vmu.io.scalar_check.size := 0.U
  vmu.io.scalar_check.store := false.B

  // The vendored configuration uses Saturn's vector-local FP units. The old
  // Rocket shared-FPU path remains quiescent at this boundary.
  backend.io.fp_req.ready := true.B
  backend.io.fp_resp.valid := false.B
  backend.io.fp_resp.bits := 0.U.asTypeOf(new FPResult)

  scalarArbiter.io.in(0) <> backend.io.scalar_resp
  scalarArbiter.io.in(1) <> dispatcher.io.scalar_resp
  io.scalarResp <> scalarArbiter.io.out

  io.setVxsat := backend.io.set_vxsat
  io.setFflags := backend.io.set_fflags
  io.busy := backend.io.busy || vmu.io.busy
}
