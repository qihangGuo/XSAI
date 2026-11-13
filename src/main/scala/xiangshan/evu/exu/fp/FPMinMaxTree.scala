package xiangshan.evu.exu

import chisel3._
import chisel3.util._
import xiangshan.evu.common.VectorFastPaths

object FP32MinMax {
  def apply(first: UInt, second: UInt, minimum: Bool): (UInt, Bool) = {
    val firstNaN = first(30, 23).andR && first(22, 0).orR
    val secondNaN = second(30, 23).andR && second(22, 0).orR
    val invalid = (firstNaN && !first(22)) || (secondNaN && !second(22))
    val less = Mux(first(31) =/= second(31), first(31),
      Mux(first(31), first(30, 0) > second(30, 0), first(30, 0) < second(30, 0)))
    val zeroSign = Mux(minimum, first(31) || second(31), first(31) && second(31))
    val numeric = Mux(!first(30, 0).orR && !second(30, 0).orR,
      Cat(zeroSign, 0.U(31.W)), Mux(Mux(minimum, less, !less), first, second))
    val result = Mux(firstNaN && secondNaN, "h7fc00000".U,
      Mux(firstNaN, second, Mux(secondNaN, first, numeric)))
    (result, invalid)
  }
}

class FPMinMaxTree(datapath: Int) extends Module {
  require(datapath >= 64 && isPow2(datapath))
  val depth = VectorFastPaths.minMaxTreeDepth(datapath)
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val source = Input(UInt(datapath.W))
    val minimum = Input(Bool())
    val result = Output(UInt(32.W))
    val flags = Output(UInt(5.W))
  })
  var values: Seq[UInt] = io.source.asTypeOf(Vec(datapath / 32, UInt(32.W))).toSeq
  var invalids = Seq.fill(datapath / 32)(false.B)
  var enabled = io.valid
  var minimum = io.minimum
  for (_ <- 0 until depth) {
    val compared = if (values.size > 1) values.indices.take(values.size / 2).map { index =>
      val (result, invalid) = FP32MinMax(values(index), values(index + values.size / 2), minimum)
      (result, invalid || invalids(index) || invalids(index + values.size / 2))
    } else values.zip(invalids)
    values = compared.map { case (result, _) => RegEnable(result, enabled) }
    invalids = compared.map { case (_, invalid) => RegEnable(invalid, enabled) }
    minimum = RegEnable(minimum, enabled)
    enabled = RegNext(enabled, false.B)
  }
  io.result := values.head
  io.flags := Cat(invalids.head, 0.U(4.W))
}
