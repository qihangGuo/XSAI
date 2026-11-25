package xiangshan.evu.exu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import xiangshan.evu.common._

class ExecutionUnit(genFUs: Seq[FunctionalUnitFactory], desc: String)(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  override def desiredName = s"ExecutionUnit$desc"
  val descName: String = desc

  val fus = genFUs.map(gen => Module(gen.generate(p)))
  val nFUs = fus.size

  val pipe_fus: Seq[(PipelinedFunctionalUnit, Int)] = fus.zipWithIndex.collect { case (f: PipelinedFunctionalUnit, i: Int) => (f, i) }
  val iter_fus: Seq[(IterativeFunctionalUnit, Int)] = fus.zipWithIndex.collect { case (f: IterativeFunctionalUnit, i: Int) => (f, i) }

  val maxPipeDepth = (pipe_fus.map(_._1.depth) :+ 0).max
  val packProducer = fus.collectFirst { case f: FPFMAPipe if f.blockPort => f }
  val packConsumer = fus.collectFirst { case f: FPLowPrecisionFU if f.packInput.nonEmpty => f }
  val packOut = packProducer.map(f => IO(Output(chiselTypeOf(f.toPack.get))))
  val packBack = packProducer.map(f => IO(Input(chiselTypeOf(f.fromPack.get))))
  val pipePackSlots = packProducer.map(_ => IO(Output(Vec(maxPipeDepth, Valid(UInt(3.W))))))
  val pipeAmaxQuarters = Option.when(dLen == 512 && vParams.fp32MinMaxTree && genFUs.contains(FPCmpFactory))(
    IO(Output(Vec(maxPipeDepth, Valid(UInt(2.W))))))
  val packIn = packConsumer.map(f => IO(Input(chiselTypeOf(f.packInput.get))))
  val packedOut = packConsumer.map(f => IO(Output(chiselTypeOf(f.packResult.get))))
  packProducer.foreach { f => packOut.get := f.toPack.get; f.fromPack.get := packBack.get }
  packConsumer.foreach { f => f.packInput.get := packIn.get; packedOut.get := f.packResult.get }

  val io = IO(new Bundle {
    val iss = Flipped(Decoupled(new ExecuteMicroOpWithData(nFUs)))
    val iter_hazards = Output(Vec(iter_fus.size, Valid(new PipeHazard(maxPipeDepth))))
    val iter_write = Decoupled(new VectorWrite(dLen))
    val iterWriteVat = Output(UInt(vParams.vatSz.W))
    val iterWriteForwardable = Output(Bool())
    val pipe_write = Output(Valid(new VectorWrite(dLen)))
    val pipeWriteVat = Output(UInt(vParams.vatSz.W))
    val pipeWriteForwardable = Output(Bool())
    val acc_write = Output(Valid(new VectorWrite(dLen)))
    val acc_write_slot = Output(UInt(2.W))
    val accWriteVat = Output(UInt(vParams.vatSz.W))
    val completed = Output(Valid(UInt(vParams.vatSz.W)))
    val scalar_write = Decoupled(new ScalarWrite)

    val pipe_hazards = Output(Vec(maxPipeDepth, Valid(new PipeHazard(maxPipeDepth))))

    val shared_fp_req = Decoupled(new FPInput())
    val shared_fp_resp = Flipped(Valid(new FPResult()))

    val set_vxsat = Output(Bool())
    val set_fflags = Output(Valid(UInt(5.W)))
    val tagged_vxsat = Output(Vec(nFUs, Valid(UInt(vParams.vatSz.W))))
    val tagged_fflags = Output(Vec(nFUs, Valid(new VectorFPFlags)))
    val busy = Output(Bool())
  })

  // XSAI EVU integration does not instantiate Saturn shared scalar FP units.
  // Keep the port quiescent so the vector backend remains self-contained.
  val hasSharedFPUnits = false
  io.shared_fp_req.valid := false.B
  io.shared_fp_req.bits := DontCare

  val stalls = fus.map(_.io.stall)
  when (io.iss.valid) { assert(PopCount(io.iss.bits.fu_sel) <= 1.U) }
  io.iss.ready := !Mux1H(io.iss.bits.fu_sel, stalls)
  fus.zipWithIndex.foreach { case (fu,i) =>
    fu.io.iss.op := io.iss.bits
    fu.io.iss.valid := io.iss.valid && io.iss.bits.fu_sel(i) && !fu.io.stall
  }

  val pipe_write = WireInit(false.B)

  io.pipe_write.valid := false.B
  io.pipeWriteVat := 0.U
  io.pipeWriteForwardable := false.B
  io.pipe_write.bits := DontCare
  io.iter_write.valid := false.B
  io.iter_write.bits := DontCare
  io.iterWriteVat := 0.U
  io.iterWriteForwardable := false.B
  io.acc_write.valid := false.B
  io.acc_write.bits := DontCare
  io.acc_write_slot := 0.U
  io.accWriteVat := 0.U
  io.completed.valid := false.B
  io.completed.bits := DontCare
  io.busy := false.B
  io.set_vxsat := fus.map(_.io.set_vxsat).orR
  io.set_fflags.valid := fus.map(_.io.set_fflags.valid).orR
  io.set_fflags.bits := fus.map(f => Mux(f.io.set_fflags.valid, f.io.set_fflags.bits, 0.U)).reduce(_|_)
  for ((fu, i) <- fus.zipWithIndex) {
    io.tagged_vxsat(i).valid := fu.io.set_vxsat
    io.tagged_vxsat(i).bits := io.tagged_fflags(i).bits.vat
    io.tagged_fflags(i).valid := fu.io.set_fflags.valid
    io.tagged_fflags(i).bits.flags := fu.io.set_fflags.bits
    io.tagged_fflags(i).bits.vat := (fu match {
      case f: PipelinedFunctionalUnit => Mux1H(f.io.pipe.zipWithIndex.map { case (stage, index) =>
        (stage.valid && stage.bits.pipe_depth === index.U) -> stage.bits.vat
      })
      case f: IterativeFunctionalUnit => f.io.hazard.bits.vat
    })
  }

  val scalar_write_arb = Module(new Arbiter(new ScalarWrite, fus.size))
  scalar_write_arb.io.in.zip(fus.map(_.io.scalar_write)).foreach { case (l, r) => l <> r }
  io.scalar_write <> scalar_write_arb.io.out

  if (pipe_fus.size > 0) {
    val pipe_valids    = Seq.fill(maxPipeDepth)(RegInit(false.B))
    val pipe_bits      = Seq.fill(maxPipeDepth)(Reg(new ExecuteMicroOpWithData(nFUs)))
    // Decode controls on the same edge as pipe_bits, without adding a stage.
    // Clear every bubble so invalid FU inputs keep their original zero values.
    val pipe_fu_live   = Seq.fill(maxPipeDepth)(RegInit(0.U(nFUs.W)))
    val pipe_fu_write  = Seq.fill(maxPipeDepth)(RegInit(0.U(nFUs.W)))

    val pipe_iss = io.iss.fire && pipe_fus.map(t => io.iss.bits.fu_sel(t._2)).orR
    pipe_valids.head := pipe_iss
    pipe_fu_live.head := Mux(pipe_iss, io.iss.bits.fu_sel, 0.U)
    pipe_fu_write.head := Mux(pipe_iss && io.iss.bits.pipe_depth === 0.U,
      io.iss.bits.fu_sel, 0.U)
    when (pipe_iss) {
      pipe_bits.head      := io.iss.bits
    }

    for (i <- 1 until maxPipeDepth) {
      val fire = pipe_valids(i-1)
      val advance = fire && pipe_bits(i-1).pipe_depth =/= (i-1).U
      pipe_valids(i) := advance
      pipe_fu_live(i) := Mux(advance, pipe_bits(i-1).fu_sel, 0.U)
      pipe_fu_write(i) := Mux(advance && pipe_bits(i-1).pipe_depth === i.U,
        pipe_bits(i-1).fu_sel, 0.U)
      when (fire) {
        pipe_bits(i)      := pipe_bits(i-1)
      }
    }
    for ((fu, j) <- pipe_fus) {
      for (i <- 0 until fu.depth) {
        fu.io.pipe(i).valid := pipe_fu_live(i)(j)
        fu.io.pipe(i).bits  := Mux(pipe_fu_live(i)(j),
          pipe_bits(i), 0.U.asTypeOf(new ExecuteMicroOpWithData(nFUs)))
      }
    }

    // Selects which pipe register has the write
    val write_pipe_sel = pipe_valids.zip(pipe_bits).zipWithIndex.map { case ((v,b),i) =>
      v && b.pipe_depth === i.U
    }
    // Selects which of all FUs has the write
    val write_fu_sel = pipe_fus.map { case (_, j) => pipe_fu_write.map(_(j)).orR }
    for (i <- 0 until maxPipeDepth) {
      assert(pipe_fu_live(i) === Mux(pipe_valids(i), pipe_bits(i).fu_sel, 0.U))
      assert(pipe_fu_write(i) === Mux(write_pipe_sel(i), pipe_bits(i).fu_sel, 0.U))
    }
    assert(PopCount(write_pipe_sel) <= 1.U)
    pipe_write := write_pipe_sel.orR
    when (write_pipe_sel.orR) {
      assert(PopCount(write_fu_sel) <= 1.U)
      val acc = Mux1H(write_pipe_sel, pipe_bits.map(_.acc))
      val tail = Mux1H(write_pipe_sel, pipe_bits.map(_.tail))
      val vat = Mux1H(write_pipe_sel, pipe_bits.map(_.vat))
      val selected_write = Mux1H(write_fu_sel, pipe_fus.map(_._1.io.write.bits))
      io.pipeWriteVat := vat
      io.pipeWriteForwardable := !acc && Mux1H(write_pipe_sel, pipe_bits.map(op =>
        op.vm && !op.use_wmask && op.eidx_mask.andR))
      if (vParams.shortResultForwarding) {
        io.pipeWriteForwardable := (!acc || tail) && Mux1H(write_pipe_sel,
          pipe_bits.map(op => op.vm && !op.use_wmask)) &&
          Mux1H(write_fu_sel, pipe_fus.map(_._1.io.write.bits.mask.orR))
      }
      io.pipe_write.valid := Mux1H(write_fu_sel, pipe_fus.map(_._1.io.write.valid)) && (!acc || tail)
      io.pipe_write.bits := selected_write
      io.acc_write.valid := acc && !tail
      io.acc_write.bits := selected_write
      io.acc_write_slot := Mux1H(write_pipe_sel, pipe_bits.map(_.acc_slot))
      io.accWriteVat := vat
      io.completed.valid := tail
      io.completed.bits := vat
    }

    when (pipe_valids.orR) { io.busy := true.B }
    for (i <- 0 until maxPipeDepth) {
      io.pipe_hazards(i).valid       := pipe_valids(i)
      io.pipe_hazards(i).bits.vat    := pipe_bits(i).vat
      io.pipe_hazards(i).bits.eg     := pipe_bits(i).wvd_eg
      pipeAmaxQuarters.foreach { quarters =>
        quarters(i).valid := pipe_valids(i) && xiangshan.evu.EvuBlock.amax(pipe_bits(i).funct3, pipe_bits(i).funct6)
        quarters(i).bits := pipe_bits(i).rs1(1, 0)
      }
      pipePackSlots.foreach { slots =>
        slots(i).valid := pipe_valids(i) && xiangshan.evu.EvuBlock.pack(pipe_bits(i).funct3, pipe_bits(i).funct6)
        slots(i).bits := xiangshan.evu.EvuBlock.packSlot(pipe_bits(i).scalar)
      }
    }

    // Compress instructions need special handling
    val permFU = pipe_fus.map(_._1).collect { case f: PermuteUnit => f }.headOption.foreach { fu =>
      when (fu.io.pipe(0).valid) {
        io.pipe_hazards(0).bits.eg := fu.io.write.bits.eg
      }
    }
  }

  if (iter_fus.size > 0) {
    val iter_write_arb = Module(new Arbiter(new VectorWrite(dLen), iter_fus.size))
    iter_write_arb.io.in.zip(iter_fus.map(_._1.io.write)).foreach { case (l,r) => l <> r }
    iter_write_arb.io.out.ready := !pipe_write && io.iter_write.ready

    val acc = Mux1H(iter_write_arb.io.in.map(_.fire), iter_fus.map(_._1.io.acc))
    val tail = Mux1H(iter_write_arb.io.in.map(_.fire), iter_fus.map(_._1.io.tail))
    val vat = Mux1H(iter_write_arb.io.in.map(_.fire), iter_fus.map(_._1.io.hazard.bits.vat))
    io.iter_write.valid     := iter_write_arb.io.out.valid && (!acc || tail) && !pipe_write
    io.iter_write.bits.eg   := iter_write_arb.io.out.bits.eg
    io.iter_write.bits.mask := iter_write_arb.io.out.bits.mask
    io.iter_write.bits.data := iter_write_arb.io.out.bits.data
    io.iterWriteVat := VecInit(iter_fus.map(_._1.io.hazard.bits.vat))(iter_write_arb.io.chosen)
    io.iterWriteForwardable := !VecInit(iter_fus.map(_._1.io.acc))(iter_write_arb.io.chosen) &&
      iter_write_arb.io.out.bits.mask.andR
    if (vParams.shortResultForwarding) {
      io.iterWriteForwardable := (!VecInit(iter_fus.map(_._1.io.acc))(iter_write_arb.io.chosen) ||
        VecInit(iter_fus.map(_._1.io.tail))(iter_write_arb.io.chosen)) && iter_write_arb.io.out.bits.mask.orR
    }
    when (!pipe_write) {
      io.acc_write.valid := iter_write_arb.io.out.valid && acc
      io.acc_write.bits.eg   := Mux1H(iter_write_arb.io.in.map(_.fire), iter_fus.map(_._1.io.write.bits.eg))
      io.acc_write.bits.data := Mux1H(iter_write_arb.io.in.map(_.fire), iter_fus.map(_._1.io.write.bits.data))
      io.acc_write.bits.mask := Mux1H(iter_write_arb.io.in.map(_.fire), iter_fus.map(_._1.io.write.bits.mask))
      io.accWriteVat := vat
      io.completed.valid := iter_write_arb.io.out.fire && tail
      io.completed.bits := vat
    }
    when (iter_fus.map(_._1.io.busy).orR) { io.busy := true.B }
    for (i <- 0 until iter_fus.size) {
      io.iter_hazards(i) := iter_fus(i)._1.io.hazard
    }
  }
}
