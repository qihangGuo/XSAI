package xiangshan.evu.exu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import xiangshan.evu.common._
import xiangshan.evu.insns._
import hardfloat._

case object FPConvFactory extends FunctionalUnitFactory {
  def insns = Seq(
    FCVT_SGL.restrictSEW(1, 2),
    FCVT_NRW.restrictSEW(0, 1),
    FCVT_WID.restrictSEW(0, 1)
  ).flatten.map(_.pipelined(3))
  def generate(implicit p: Parameters) = new FPConvPipe()(p)
}

// Fixed depth 3
// s0 - convert to raw/recoded
// s1/s2 - perform conversion
class FPConvBlock(implicit p: Parameters) extends CoreModule()(p) with HasFPUParameters {
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val in = Input(UInt(64.W))
    val in_eew = Input(UInt(2.W))
    val widen = Input(Bool())
    val narrow = Input(Bool())
    val signed = Input(Bool())
    val frm = Input(UInt(3.W))
    val i2f = Input(Bool())
    val f2i = Input(Bool())
    val truncating = Input(Bool())
    val rto = Input(Bool())

    val out = Output(UInt(64.W))
    val exc = Output(Vec(8, UInt(FPConstants.FLAGS_SZ.W)))
  })

  def f2raw(t: FType, in: UInt) = rawFloatFromFN(t.exp, t.sig, in)
  def raw2raw(t: FType, in: RawFloat) = {
    val out = WireInit(resizeRawFloat(t.exp, t.sig, in))
    // workaround bug in resizeRawFloat
    when (in.isNaN || in.isInf) {
      out.sExp := ((1 << t.exp) + (1 << (t.exp - 1))).U(t.exp,0).zext
    }
    out
  }
  def raw2rec(t: FType, rawIn: RawFloat) = {
    (rawIn.sign ##
      (Mux(rawIn.isZero, 0.U(3.W), rawIn.sExp(t.exp, t.exp - 2)) |
        Mux(rawIn.isNaN, 1.U, 0.U)) ##
      rawIn.sExp(t.exp - 3, 0) ##
      rawIn.sig(t.sig - 2, 0)
    )
  }

  val s0_out_eew = Mux(io.widen, io.in_eew + 1.U, Mux(io.narrow, io.in_eew - 1.U, io.in_eew))

  val in32 = io.in.asTypeOf(Vec(2, UInt(32.W)))
  val in16 = io.in.asTypeOf(Vec(4, UInt(16.W)))

  val raw32 = VecInit(in32.map(u => f2raw(FType.S, u)))
  val raw16 = VecInit(in16.map(u => f2raw(FType.H, u)))

  val raw16as32 = VecInit(raw16.map(r => raw2raw(FType.S, r)))

  val s2i = Seq.fill(2)(Module(new RecFNToINDynamic(FType.S.exp, FType.S.sig, Seq(8, 16, 32))))
  val h2i = Seq.fill(2)(Module(new RecFNToINDynamic(FType.H.exp, FType.H.sig, Seq(8, 16))))

  s2i.zipWithIndex.foreach { case (f, i) =>
    f.io.in := RegEnable(raw2rec(FType.S,
      Mux(io.in_eew === 2.U, raw32(i), raw16as32(2*i))
    ), io.valid)
  }
  h2i.zipWithIndex.foreach { case (f, i) =>
    f.io.in := RegEnable(raw2rec(FType.H, raw16(2*i+1)), io.valid)
  }

  val s1_out_eew = RegEnable(s0_out_eew, io.valid)
  val s1_signed = RegEnable(io.signed, io.valid)
  val s1_frm = RegEnable(io.frm, io.valid)
  val s1_truncating = RegEnable(io.truncating, io.valid)
  val s1_rto = RegEnable(io.rto, io.valid)
  val s1_i2f = RegEnable(io.i2f, io.valid)
  val s1_f2i = RegEnable(io.f2i, io.valid)
  val s1_valid = RegNext(io.valid, false.B)

  s2i.foreach(_.io.outW := (s1_out_eew === 2.U) ## (s1_out_eew === 1.U) ## (s1_out_eew === 0.U))
  h2i.foreach(_.io.outW := (s1_out_eew === 1.U) ## (s1_out_eew === 0.U))

  (s2i ++ h2i).foreach { f2i =>
    f2i.io.signedOut := s1_signed
    f2i.io.roundingMode := Mux(s1_truncating, 1.U, s1_frm)
  }

  val i2s = Seq(
    Module(new hardfloat.INToRecFN(32, FType.S.exp, FType.S.sig)),
    Module(new hardfloat.INToRecFN(32, FType.S.exp, FType.S.sig))
  )
  val i2h = Seq(
    Module(new hardfloat.INToRecFN(32, FType.H.exp, FType.H.sig)),
    Module(new hardfloat.INToRecFN(16, FType.H.exp, FType.H.sig)),
    Module(new hardfloat.INToRecFN(32, FType.H.exp, FType.H.sig)),
    Module(new hardfloat.INToRecFN(16, FType.H.exp, FType.H.sig))
  )

  def sext(w: Int, in: UInt): UInt = if (w == in.getWidth) in else
    Fill(w - in.getWidth, io.signed && in(in.getWidth-1)) ## in

  i2s.zipWithIndex.foreach { case (f, i) =>
    f.io.in := RegEnable(Mux(io.in_eew === 1.U, sext(32, in16(2*i)), in32(i)), io.valid)
  }
  i2h.zipWithIndex.foreach { case (f, i) =>
    val w = if (i % 2 == 0) 32 else 16
    val narrowInput = if (i % 2 == 0) in32(i/2) else 0.U(w.W)
    f.io.in := RegEnable(Mux(io.in_eew === 0.U, sext(w, in16(i)(7,0)),
      Mux(io.in_eew === 2.U, narrowInput, sext(w, in16(i)))), io.valid)
  }

  (i2h ++ i2s).foreach { i2f =>
    i2f.io.signedIn := s1_signed
    i2f.io.roundingMode := s1_frm
    i2f.io.detectTininess := hardfloat.consts.tininess_afterRounding
  }

  val h2s = Seq.fill(2)(Module(new hardfloat.RecFNToRecFN(FType.H.exp, FType.H.sig, FType.S.exp, FType.S.sig)))
  val s2h = Seq.fill(2)(Module(new hardfloat.RecFNToRecFN(FType.S.exp, FType.S.sig, FType.H.exp, FType.H.sig)))

  h2s(0).io.in := RegEnable(raw2rec(FType.H, raw16(0)), io.valid)
  h2s(1).io.in := RegEnable(raw2rec(FType.H, raw16(2)), io.valid)
  s2h(0).io.in := RegEnable(raw2rec(FType.S, raw32(0)), io.valid)
  s2h(1).io.in := RegEnable(raw2rec(FType.S, raw32(1)), io.valid)

  (h2s ++ s2h).foreach { f2f =>
    f2f.io.roundingMode := s1_frm
    f2f.io.detectTininess := hardfloat.consts.tininess_afterRounding
  }
  s2h.foreach { f2f =>
    f2f.io.roundingMode := Mux(s1_rto, "b110".U, s1_frm)
  }

  val out = WireInit(0.U(64.W))
  val exc = WireInit(0.U.asTypeOf(Vec(8, UInt(FPConstants.FLAGS_SZ.W))))

  io.out := out
  io.exc := exc

  def toExc(iFlags: UInt) = Cat(iFlags(2,1).orR, 0.U(3.W), iFlags(0))

  val i2s_out = i2s.map(f => RegEnable(FType.S.ieee(f.io.out), s1_valid))
  val i2h_out = i2h.map(f => RegEnable(FType.H.ieee(f.io.out), s1_valid))
  val i2s_exc = i2s.map(f => RegEnable(f.io.exceptionFlags, s1_valid))
  val i2h_exc = i2h.map(f => RegEnable(f.io.exceptionFlags, s1_valid))

  val s2i_out = s2i.map(f => RegEnable(f.io.out, s1_valid))
  val h2i_out = h2i.map(f => RegEnable(f.io.out, s1_valid))
  val s2i_exc = s2i.map(f => RegEnable(toExc(f.io.intExceptionFlags), s1_valid))
  val h2i_exc = h2i.map(f => RegEnable(toExc(f.io.intExceptionFlags), s1_valid))

  val h2s_out = h2s.map(f => RegEnable(FType.S.ieee(f.io.out), s1_valid))
  val s2h_out = s2h.map(f => RegEnable(FType.H.ieee(f.io.out), s1_valid))
  val h2s_exc = h2s.map(f => RegEnable(f.io.exceptionFlags, s1_valid))
  val s2h_exc = s2h.map(f => RegEnable(f.io.exceptionFlags, s1_valid))

  val s2_i2f = RegEnable(s1_i2f, s1_valid)
  val s2_f2i = RegEnable(s1_f2i, s1_valid)
  val s2_widen = RegEnable(RegEnable(io.widen, io.valid), s1_valid)
  val s2_out_eew = RegEnable(s1_out_eew, s1_valid)

  when (s2_i2f) {
    when (s2_out_eew === 2.U) {
      out := VecInit(i2s_out).asUInt
      for (i <- 0 until 8) { exc(i) := i2s_exc(i/4) }
    }
    when (s2_out_eew === 1.U) {
      out := VecInit(i2h_out).asUInt
      for (i <- 0 until 8) { exc(i) := i2h_exc(i/2) }
    }
  } .elsewhen (s2_f2i) {
    when (s2_out_eew === 2.U) {
      out := VecInit(s2i_out.map(_(31,0))).asUInt
      for (i <- 0 until 8) { exc(i) := s2i_exc(i/4) }
    }
    // Narrowing leaves results in the low part of each source element.
    // FPConvPipe compacts those slots after this block.
    when (s2_out_eew <= 1.U) {
      val halfResults = Seq(s2i_out(0)(15,0), h2i_out(0)(15,0),
        s2i_out(1)(15,0), h2i_out(1)(15,0))
      out := VecInit(halfResults).asUInt
      val halfFlags = Seq(s2i_exc(0), h2i_exc(0), s2i_exc(1), h2i_exc(1))
      for (i <- 0 until 8) { exc(i) := halfFlags(i/2) }
    }
  } .otherwise {
    when (s2_widen && s2_out_eew === 2.U) {
      out := VecInit(h2s_out).asUInt
      for (i <- 0 until 8) { exc(i) := h2s_exc(i/4) }
    }
    when (s2_out_eew === 1.U) {
      out := VecInit(s2h_out.map(o => 0.U(16.W) ## o)).asUInt
      for (i <- 0 until 8) { exc(i) := s2h_exc(i/4) }
    }
  }
}

class FPConvPipe(implicit p: Parameters) extends PipelinedFunctionalUnit(3)(p) with HasFPUParameters {
  val supported_insns = FPConvFactory.insns

  io.set_vxsat := false.B
  io.stall := false.B

  val rs1 = io.pipe(0).bits.rs1
  val ctrl_widen = rs1(3)
  val ctrl_narrow = rs1(4)
  val ctrl_signed = rs1(0)
  val ctrl_i2f = !rs1(2) && rs1(1)
  val ctrl_f2i = (!rs1(2) && !rs1(1)) || (rs1(2) && rs1(1))
  val ctrl_truncating = rs1(2) && rs1(1)
  val ctrl_round_to_odd = rs1(0)

  val rvs2_data = io.pipe(0).bits.rvs2_data
  val vd_eew = io.pipe(0).bits.vd_eew
  val rvs2_eew = io.pipe(0).bits.rvs2_eew

  val hi = (io.pipe(0).bits.eidx >> (dLenOffBits.U - vd_eew))(0)
  val expanded_rvs2_data = narrow2_expand(rvs2_data.asTypeOf(Vec(dLenB, UInt(8.W))), rvs2_eew,
    hi, false.B).asUInt

  val conv_blocks = Seq.fill(dLen/64) { Module(new FPConvBlock) }
  conv_blocks.zipWithIndex.foreach { case (c,i) =>
    c.io.valid := io.pipe(0).valid
    c.io.in := Mux(ctrl_widen,
      expanded_rvs2_data,
      rvs2_data)((i*64)+63,i*64)

    c.io.in_eew := rvs2_eew
    c.io.widen := ctrl_widen
    c.io.narrow := ctrl_narrow
    c.io.signed := ctrl_signed
    c.io.frm := io.pipe(0).bits.frm
    c.io.i2f := ctrl_i2f
    c.io.f2i := ctrl_f2i
    c.io.truncating := ctrl_truncating
    c.io.rto := ctrl_round_to_odd
  }

  val out = Wire(UInt(dLen.W))
  val exc = Wire(Vec(dLenB, UInt(FPConstants.FLAGS_SZ.W)))

  val s2_ctrl_narrow = io.pipe(2).bits.rs1(4)
  val s2_vd_eew = io.pipe(2).bits.vd_eew
  when (s2_ctrl_narrow) {
    val bits = VecInit(conv_blocks.map(_.io.out)).asUInt
    val out8  = VecInit(bits.asTypeOf(Vec(dLenB     , UInt( 8.W))).grouped(2).map(_.head).toSeq).asUInt
    val out16 = VecInit(bits.asTypeOf(Vec(dLenB >> 1, UInt(16.W))).grouped(2).map(_.head).toSeq).asUInt
    out := Fill(2, Mux1H(Seq(
      (s2_vd_eew === 0.U) -> out8,
      (s2_vd_eew === 1.U) -> out16
    )))
    val excs = VecInit(conv_blocks.map(_.io.exc).flatten)
    val exc_out = Mux1H(Seq(
      (s2_vd_eew === 0.U) -> VecInit(excs.grouped(2).map(_.take(1)).flatten.toSeq),
      (s2_vd_eew === 1.U) -> VecInit(excs.grouped(4).map(_.take(2)).flatten.toSeq),
    ))

    exc := VecInit(Seq(exc_out, exc_out).flatten)
  } .otherwise {
    out := VecInit(conv_blocks.map(_.io.out)).asUInt
    exc := VecInit(conv_blocks.map(_.io.exc).flatten)
  }



  io.write.valid := io.pipe(depth-1).valid
  io.write.bits.eg := io.pipe(depth-1).bits.wvd_eg
  io.write.bits.mask := FillInterleaved(8, io.pipe(depth-1).bits.wmask)
  io.write.bits.data := out

  io.set_fflags.valid := io.write.valid
  io.set_fflags.bits := (0 until dLenB).map { i => Mux(io.pipe(depth-1).bits.wmask(i), exc(i), 0.U) }.reduce(_|_)
  io.scalar_write.valid := false.B
  io.scalar_write.bits := DontCare
}
