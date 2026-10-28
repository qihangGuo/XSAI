package xiangshan.evu

import chisel3._
import chisel3.util._
import hardfloat.{CompareRecFN, MulAddRecFN, RecFNToIN, recFNFromFN}

class EvuFpQuantInput(p: EvuParams) extends Bundle {
  val input = UInt(p.dlenBits.W)
  val scale = UInt(p.dlenBits.W)
  val validLanes = UInt((p.dlenBits / 32).W)
  val chunk = UInt(log2Ceil(p.vlenBits / p.dlenBits).W)
}

class EvuFpQuantResult(p: EvuParams) extends Bundle {
  val data = UInt((p.dlenBits / 4).W)
  val validLanes = UInt((p.dlenBits / 32).W)
  val chunk = UInt(log2Ceil(p.vlenBits / p.dlenBits).W)
  val fflags = UInt(5.W)
}

/** Three elastic stages: rounded multiply, rounded add, clamp/RTZ/pack.
  * One group per cycle when operands and the downstream consumer are ready.
  * Separate rounding and active-lane flags match the NEMU SoftFloat oracle.
  */
class EvuFpQuant(p: EvuParams) extends Module {
  private val lanes = p.dlenBits / 32
  class Rounded extends Bundle {
    val values = Vec(lanes, UInt(33.W))
    val validLanes = UInt(lanes.W)
    val chunk = UInt(log2Ceil(p.vlenBits / p.dlenBits).W)
    val fflags = UInt(5.W)
  }
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new EvuFpQuantInput(p)))
    val out = Decoupled(new EvuFpQuantResult(p))
    val flush = Input(Bool())
    val busy = Output(Bool())
  })
  val product = Module(new EvuElasticStage(new Rounded))
  val sum = Module(new EvuElasticStage(new Rounded))
  val packed = Module(new EvuElasticStage(new EvuFpQuantResult(p)))
  Seq(product.io.flush, sum.io.flush, packed.io.flush).foreach(_ := io.flush)
  product.io.in.valid := io.in.valid
  io.in.ready := product.io.in.ready
  product.io.in.bits.validLanes := io.in.bits.validLanes
  product.io.in.bits.chunk := io.in.bits.chunk
  sum.io.in.valid := product.io.out.valid
  product.io.out.ready := sum.io.in.ready
  sum.io.in.bits.validLanes := product.io.out.bits.validLanes
  sum.io.in.bits.chunk := product.io.out.bits.chunk
  packed.io.in.valid := sum.io.out.valid
  sum.io.out.ready := packed.io.in.ready
  packed.io.in.bits.validLanes := sum.io.out.bits.validLanes
  packed.io.in.bits.chunk := sum.io.out.bits.chunk
  io.out <> packed.io.out
  io.busy := product.io.out.valid || sum.io.out.valid || packed.io.out.valid

  val inputs = io.in.bits.input.asTypeOf(Vec(lanes, UInt(32.W)))
  val scales = io.in.bits.scale.asTypeOf(Vec(lanes, UInt(32.W)))
  val bytes = Wire(Vec(lanes, UInt(8.W)))
  val mulFlags = Wire(Vec(lanes, UInt(5.W)))
  val addFlags = Wire(Vec(lanes, UInt(5.W)))
  val packFlags = Wire(Vec(lanes, UInt(5.W)))
  for (i <- 0 until lanes) {
    val mul = Module(new MulAddRecFN(8, 24))
    mul.io.op := 0.U
    mul.io.a := recFNFromFN(8, 24, inputs(i))
    mul.io.b := recFNFromFN(8, 24, scales(i))
    mul.io.c := recFNFromFN(8, 24, 0.U(32.W))
    mul.io.roundingMode := 0.U
    mul.io.detectTininess := 1.U
    product.io.in.bits.values(i) := mul.io.out
    mulFlags(i) := Mux(io.in.bits.validLanes(i), mul.io.exceptionFlags, 0.U)

    val add = Module(new MulAddRecFN(8, 24))
    add.io.op := 0.U
    add.io.a := product.io.out.bits.values(i)
    add.io.b := recFNFromFN(8, 24, "h3f800000".U(32.W))
    add.io.c := recFNFromFN(8, 24, "h3f000000".U(32.W))
    add.io.roundingMode := 0.U
    add.io.detectTininess := 1.U
    sum.io.in.bits.values(i) := add.io.out
    addFlags(i) := Mux(product.io.out.bits.validLanes(i), add.io.exceptionFlags, 0.U)

    val value = sum.io.out.bits.values(i)
    val upper = recFNFromFN(8, 24, "h42fe0000".U(32.W))
    val zero = recFNFromFN(8, 24, 0.U(32.W))
    val cmp = Module(new CompareRecFN(8, 24))
    cmp.io.a := value
    cmp.io.b := upper
    cmp.io.signaling := false.B
    val capped = Mux(cmp.io.lt || cmp.io.eq, value, upper)
    val lower = Module(new CompareRecFN(8, 24))
    lower.io.a := capped
    lower.io.b := zero
    lower.io.signaling := false.B
    val convert = Module(new RecFNToIN(8, 24, 32))
    convert.io.in := Mux(lower.io.lt, zero, capped)
    convert.io.roundingMode := 1.U
    convert.io.signedOut := false.B
    val conversionFlags = Cat(convert.io.intExceptionFlags(2, 1).orR, 0.U(3.W), convert.io.intExceptionFlags(0))
    bytes(i) := Mux(sum.io.out.bits.validLanes(i), convert.io.out(7, 0), 0.U)
    packFlags(i) := Mux(sum.io.out.bits.validLanes(i),
      cmp.io.exceptionFlags | lower.io.exceptionFlags | conversionFlags, 0.U)
  }
  product.io.in.bits.fflags := mulFlags.reduce(_ | _)
  sum.io.in.bits.fflags := product.io.out.bits.fflags | addFlags.reduce(_ | _)
  packed.io.in.bits.data := bytes.asUInt
  packed.io.in.bits.fflags := sum.io.out.bits.fflags | packFlags.reduce(_ | _)
}
