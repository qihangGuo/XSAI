package xiangshan.evu

import chisel3._
import hardfloat.{INToRecFN, MulAddRecFN, fNFromRecFN, recFNFromFN}
import chisel3.util._

/** Fixed-point and FP32 post-processing block used by the inference path. */
object EvuQuantOp {
  val Dequant  = 1.U(3.W)
  val Requant  = 3.U(3.W)
  val Sum      = 4.U(3.W)
  val Max      = 5.U(3.W)
  val Min      = 6.U(3.W)
}

class EvuQuantIO(p: EvuParams) extends Bundle {
  val in       = Flipped(Decoupled(UInt(p.dlenBits.W)))
  val scale    = Input(UInt(p.dlenBits.W))
  val bias     = Input(UInt(p.dlenBits.W))
  val zero     = Input(SInt(32.W))
  val shift    = Input(UInt(6.W))
  val sew      = Input(UInt(2.W))
  val op       = Input(UInt(3.W))
  val floating = Input(Bool())
  val scaleProduct = Input(Bool())
  val foldLog2e = Input(Bool())
  val broadcastIndex = Input(UInt(log2Ceil(p.dlenBits / 32).W))
  val validLanes = Input(UInt((p.dlenBits / 32).W))
  val out      = Decoupled(UInt(p.dlenBits.W))
  val saturated = Output(Bool())
  val fflags = Output(UInt(5.W))
}

/**
  * One DLEN-wide vector post-processing beat.  The block intentionally has a
  * decoupled boundary so it can sit behind CUTE's microblock FIFO or be
  * chained directly after an EVU load.  Arithmetic is signed fixed-point;
  * FP32 dequantization uses HardFloat and reports IEEE exception flags.
  */
class EvuQuant(p: EvuParams) extends Module {
  val io = IO(new EvuQuantIO(p))
  val elems = p.dlenBits / 32
  val a = io.in.bits.asTypeOf(Vec(elems, SInt(32.W)))
  val scale = io.scale.asTypeOf(Vec(elems, SInt(32.W)))
  val bias = io.bias.asTypeOf(Vec(elems, SInt(32.W)))
  val result = Wire(Vec(elems, SInt(32.W)))
  val saturated = WireInit(false.B)
  val fpResult = Wire(Vec(elems, UInt(32.W)))
  val fpFlags = Wire(Vec(elems, UInt(5.W)))

  for (i <- 0 until elems) {
    val intToFp = Module(new INToRecFN(32, 8, 24))
    intToFp.io.signedIn := true.B
    intToFp.io.in := a(i).asUInt
    intToFp.io.roundingMode := 0.U
    intToFp.io.detectTininess := 1.U
    val fma = Module(new MulAddRecFN(8, 24))
    val scaleFold = Module(new MulAddRecFN(8, 24))
    scaleFold.io.op := 0.U
    scaleFold.io.a := recFNFromFN(8, 24, scale(i).asUInt)
    scaleFold.io.b := recFNFromFN(8, 24, "h3fb8aa3b".U(32.W)) // log2(e)
    scaleFold.io.c := recFNFromFN(8, 24, 0.U(32.W))
    scaleFold.io.roundingMode := 0.U
    scaleFold.io.detectTininess := 1.U
    fma.io.op := 0.U
    fma.io.a := intToFp.io.out
    fma.io.b := Mux(io.foldLog2e,
      scaleFold.io.out,
      recFNFromFN(8, 24, scale(i).asUInt))
    fma.io.c := recFNFromFN(8, 24, Mux(io.scaleProduct, 0.U, bias(i).asUInt))
    fma.io.roundingMode := 0.U
    fma.io.detectTininess := 1.U
    val scaleBFma = Module(new MulAddRecFN(8, 24))
    scaleBFma.io.op := 0.U
    scaleBFma.io.a := fma.io.out
    scaleBFma.io.b := recFNFromFN(8, 24, bias(io.broadcastIndex).asUInt)
    scaleBFma.io.c := recFNFromFN(8, 24, 0.U(32.W))
    scaleBFma.io.roundingMode := 0.U
    scaleBFma.io.detectTininess := 1.U
    fpResult(i) := fNFromRecFN(8, 24, Mux(io.scaleProduct, scaleBFma.io.out, fma.io.out))
    fpFlags(i) := Mux(io.validLanes(i),
      intToFp.io.exceptionFlags | Mux(io.foldLog2e, scaleFold.io.exceptionFlags, 0.U) |
        fma.io.exceptionFlags | Mux(io.scaleProduct, scaleBFma.io.exceptionFlags, 0.U), 0.U)
    val product = (a(i) - io.zero) * scale(i)
    val shifted = (product + bias(i)) >> io.shift
    val converted = Wire(SInt(32.W))
    converted := a(i)
    when(io.op === EvuQuantOp.Dequant) { converted := shifted }
    when(io.op === EvuQuantOp.Requant) {
      val maxValue = MuxLookup(io.sew, 2147483647.S(32.W))(Seq(
        0.U -> 127.S(32.W),
        1.U -> 32767.S(32.W),
        2.U -> 2147483647.S(32.W)
      ))
      val minValue = MuxLookup(io.sew, (-2147483648L).S(32.W))(Seq(
        0.U -> (-128).S(32.W),
        1.U -> (-32768).S(32.W),
        2.U -> (-2147483648L).S(32.W)
      ))
      val clamped = Mux(shifted > maxValue, maxValue, Mux(shifted < minValue, minValue, shifted))
      converted := clamped + io.zero
      when(io.validLanes(i) && (shifted > maxValue || shifted < minValue)) { saturated := true.B }
    }
    result(i) := converted
  }

  val reduce = Wire(SInt(32.W))
  reduce := result.map(_.asSInt).reduce(_ +& _)
  when(io.op === EvuQuantOp.Max) { reduce := result.map(_.asSInt).reduce((x, y) => Mux(x > y, x, y)) }
  when(io.op === EvuQuantOp.Min) { reduce := result.map(_.asSInt).reduce((x, y) => Mux(x < y, x, y)) }
  val output = Wire(UInt(p.dlenBits.W))
  output := result.asUInt
  when(io.op === EvuQuantOp.Dequant && io.floating) { output := fpResult.asUInt }
  when(io.op === EvuQuantOp.Sum || io.op === EvuQuantOp.Max || io.op === EvuQuantOp.Min) {
    output := Cat(Seq.fill(elems - 1)(0.U(32.W)) :+ reduce.asUInt)
  }
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid
  io.out.bits := output
  io.saturated := saturated
  io.fflags := Mux(io.floating && io.op === EvuQuantOp.Dequant, fpFlags.reduce(_ | _), 0.U)
}
