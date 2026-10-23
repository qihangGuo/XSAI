package xiangshan.evu.backend

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tile.{CoreModule}
import freechips.rocketchip.util._
import xiangshan.evu.common._

class RegisterAccess(exSeqs: Int, maxExuDepth: Int, specialReads: Int = 0)(implicit p: Parameters)
    extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val vls = new Bundle {
      val rvm = Flipped(new VectorReadIO)
    }
    val vss = new Bundle {
      val rvd = Flipped(new VectorReadIO)
      val rvm = Flipped(new VectorReadIO)
    }
    val vxs = Vec(exSeqs, new Bundle {
      val rvs1 = Flipped(new VectorReadIO)
      val rvs2 = Flipped(new VectorReadIO)
      val rvd = Flipped(new VectorReadIO)
      val rvm = Flipped(new VectorReadIO)
      val pipe_write_req = Flipped(new VectorPipeWriteReqIO(maxExuDepth))
    })
    val vps = new Bundle {
      val rvs2 = Flipped(new VectorReadIO)
      val rvm = Flipped(new VectorReadIO)
    }

    val frontend = new Bundle {
      val rindex = Flipped(new VectorReadIO)
      val rmask = Flipped(new VectorReadIO)
    }

    val pipe_writes = Vec(exSeqs, Input(Valid(new VectorWrite(dLen))))
    val iter_writes = Vec(exSeqs, Flipped(Decoupled(new VectorWrite(dLen))))
    val load_write = Flipped(Decoupled(new VectorWrite(dLen)))
    val debug = Option.when(vParams.debugVrf)(Output(Vec(egsTotal, UInt(dLen.W))))
    val special = new Bundle {
      val read = Vec(specialReads, Flipped(new VectorReadIO))
    }
  })

  // The execute path has a logical 3R1W operand shape.  RegisterFile maps
  // these logical requests onto the Ara-style 1RW banks when enabled.
  //  read0 arbitrates between <- [vxs-rs1], vmu-index
  //  read1 arbitrates between <- [vxs-rs2], frontend-index
  //  read2 arbitrates between <- [vxs-rs3], vss-vrd

  // Mask read arbitrates between <- [vxs], vls, vss, vps, frontend-mask

  val vrf = Module(new RegisterFile(
    reads = Seq(1 + exSeqs, 1 + exSeqs, 1 + exSeqs) ++ (if (specialReads > 0) Seq(specialReads) else Seq.empty),
    maskReads = Seq(4 + exSeqs),
    pipeWrites = exSeqs,
    llWrites = exSeqs + 2, // load + reset
    maxExuDepth
  ))

  val resetting = RegInit(true.B)
  io.debug.foreach(_ := vrf.io.debug.get)
  val reset_ctr = RegInit(0.U(log2Ceil(egsTotal).W))

  // LL writes.  The reset write is a normal VRF transaction; advancing the
  // reset address before it fires would skip rows when a bank is busy.
  vrf.io.ll_writes(0).valid := resetting
  vrf.io.ll_writes(0).bits.eg := reset_ctr
  vrf.io.ll_writes(0).bits.data := 0.U
  vrf.io.ll_writes(0).bits.mask := ~(0.U(dLen.W))
  vrf.io.ll_writes(1).valid := io.load_write.valid && !resetting
  vrf.io.ll_writes(1).bits := io.load_write.bits
  io.load_write.ready := vrf.io.ll_writes(1).ready && !resetting
  for (i <- 0 until exSeqs) {
    vrf.io.ll_writes(2+i).valid := io.iter_writes(i).valid && !resetting
    vrf.io.ll_writes(2+i).bits := io.iter_writes(i).bits
    io.iter_writes(i).ready := vrf.io.ll_writes(2+i).ready && !resetting
  }

  // Pipe writes
  for (i <- 0 until exSeqs) {
    vrf.io.pipe_write_reqs(i) <> io.vxs(i).pipe_write_req
    vrf.io.pipe_writes(i).valid := io.pipe_writes(i).valid && !resetting
    vrf.io.pipe_writes(i).bits := io.pipe_writes(i).bits
  }

  // Reads
  for (i <- 0 until exSeqs) {
    vrf.io.read(0)(i) <> io.vxs(i).rvs1
    vrf.io.read(1)(i) <> io.vxs(i).rvs2
    vrf.io.read(2)(i) <> io.vxs(i).rvd
    vrf.io.mask_read(0)(i) <> io.vxs(i).rvm
  }

  vrf.io.read(0)(exSeqs) <> io.vps.rvs2
  vrf.io.read(1)(exSeqs) <> io.frontend.rindex
  vrf.io.read(2)(exSeqs) <> io.vss.rvd

  if (specialReads > 0) {
    for (i <- 0 until specialReads) {
      vrf.io.read(3)(i) <> io.special.read(i)
    }
  }

  vrf.io.mask_read(0)(exSeqs)   <> io.vls.rvm
  vrf.io.mask_read(0)(exSeqs+1) <> io.vss.rvm
  vrf.io.mask_read(0)(exSeqs+2) <> io.vps.rvm
  vrf.io.mask_read(0)(exSeqs+3) <> io.frontend.rmask

  when (resetting) {
    vrf.io.read.foreach(_.foreach(_.req.valid := false.B))
    vrf.io.mask_read.foreach(_.foreach(_.req.valid := false.B))
    io.vls.rvm.req.ready := false.B
    io.vss.rvd.req.ready := false.B
    io.vss.rvm.req.ready := false.B
    io.vxs.foreach(_.rvs1.req.ready := false.B)
    io.vxs.foreach(_.rvs2.req.ready := false.B)
    io.vxs.foreach(_.rvd.req.ready := false.B)
    io.vxs.foreach(_.rvm.req.ready := false.B)
    io.vps.rvs2.req.ready := false.B
    io.vps.rvm.req.ready := false.B
    io.frontend.rindex.req.ready := false.B
    io.frontend.rmask.req.ready := false.B
    io.special.read.foreach(_.req.ready := false.B)
    io.load_write.ready := false.B
    io.iter_writes.foreach(_.ready := false.B)
    io.vxs.foreach(_.pipe_write_req.available := false.B)
    // Pipe writes have no ready channel; their valid bits are gated above.
    when (vrf.io.ll_writes(0).fire) {
      when (reset_ctr === (egsTotal - 1).U) {
        resetting := false.B
      } .otherwise {
        reset_ctr := reset_ctr + 1.U
      }
    }
  }


}
