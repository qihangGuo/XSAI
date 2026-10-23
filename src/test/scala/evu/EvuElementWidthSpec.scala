package evu

import chisel3._
import circt.stage.ChiselStage
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common.VectorParams
import xiangshan.evu.exu._
import xiangshan.evu.insns._

class EvuWidthConfigHarness extends Module {
  val io = IO(new Bundle {
    val sew = Input(UInt(3.W))
    val vl = Input(UInt(32.W))
    val aux = Input(UInt(8.W))
    val illegal = Output(Bool())
    val configuredVl = Output(UInt(32.W))
  })
  val (vl, _, illegal) = EvuVtype.configure(EvuParams(), true.B, io.sew, io.vl, io.aux)
  io.illegal := illegal
  io.configuredVl := vl
}

/** Interpreter/CHIRRTL checks only: this suite never emits Verilog. */
class EvuElementWidthSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU with at most 32-bit elements"

  private val base = Parameters.empty.alterPartial {
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }
  private implicit val p: Parameters = EvuVectorParameters(base, 4096, 256, 128,
    VectorParams.lgvParams)

  private def pack(values: Seq[BigInt], width: Int): BigInt =
    values.zipWithIndex.map { case (v, i) => (v & ((BigInt(1) << width) - 1)) << (width*i) }.reduce(_ | _)
  private def single(v: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(v).toLong & 0xffffffffL)

  it should "reject a 64-bit vtype and clear its VL" in {
    test(new EvuWidthConfigHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.sew.poke(0.U)
      dut.io.vl.poke(32.U)
      for (sew <- 0 to 7) {
        dut.io.aux.poke((sew << 3).U)
        dut.io.illegal.expect((sew > 2).B)
        dut.io.configuredVl.expect((if (sew > 2) 0 else 32).U)
      }
    }
  }

  it should "elaborate every retained arithmetic unit without a double precision or 64-bit multiplier" in {
    val factories = Seq(IntegerPipeFactory, IntegerMultiplyFactory(3, true), IntegerMultiplyFactory(3, false),
      ShiftPipeFactory, BitwisePipeFactory, BitmanipPipeFactory, PermuteUnitFactory, MaskUnitFactory(1),
      SIMDFPFMAFactory(4, false), SIMDFPFMAFactory(4, true), FPCmpFactory, FPConvFactory, FPDivSqrtFactory,
      VFExp2Factory)
    for (factory <- factories) {
      for (insn <- factory.insns) {
        insn.lookup(SEW).mask should be(BigInt(3))
        val sew = insn.lookup(SEW).value.toInt
        sew should be <= 2
        if (insn.lookup(Wide2VD).value == 1 || insn.lookup(Wide2VS2).value == 1) sew should be <= 1
      }
      val ir = ChiselStage.emitCHIRRTL(factory.generate)
      ir should not include "e11_s53"
      ir should not include "expWidth = 11"
      ir should not include "sigWidth = 53"
      ir.split("(?m)^  module ").filter(_.startsWith("Multiplier")).foreach { multiplier =>
        multiplier should not include "in1 : UInt<64>"
      }
    }
  }

  it should "preserve signed and unsigned packed multiplication including full INT32 products" in {
    test(new MultiplyBlock).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val random = new scala.util.Random(0x1234)
      for (sew <- 0 to 2; signed1 <- Seq(false, true); signed2 <- Seq(false, true); trial <- 0 until 12) {
        val width = 8 << sew
        val count = 64/width
        val mask = (BigInt(1) << width) - 1
        val a = Seq.fill(count)(BigInt(width, random))
        val b = Seq.fill(count)(BigInt(width, random))
        def asSigned(v: BigInt, signed: Boolean): BigInt =
          if (signed && v.testBit(width-1)) v - (BigInt(1) << width) else v
        val expected = pack(a.zip(b).map { case (x, y) => asSigned(x, signed1)*asSigned(y, signed2) }, 2*width)
        dut.io.eew.poke(sew.U)
        dut.io.in1_signed.poke(signed1.B)
        dut.io.in2_signed.poke(signed2.B)
        dut.io.in1.poke(pack(a, width).U)
        dut.io.in2.poke(pack(b, width).U)
        dut.io.out_data.expect(expected.U)
      }
    }
  }

  it should "convert every FP16 and FP32 slot with widening and narrowing" in {
    test(new FPConvBlock).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.valid.poke(false.B)
      dut.io.truncating.poke(false.B)
      dut.io.rto.poke(false.B)
      dut.io.signed.poke(true.B)
      def run(in: BigInt, eew: Int, widen: Boolean, narrow: Boolean, i2f: Boolean, f2i: Boolean,
          expected: BigInt, mask: BigInt = (BigInt(1) << 64)-1, frm: Int = 0, flags: Int = 0): Unit = {
        dut.io.in.poke(in.U)
        dut.io.in_eew.poke(eew.U)
        dut.io.widen.poke(widen.B)
        dut.io.narrow.poke(narrow.B)
        dut.io.i2f.poke(i2f.B)
        dut.io.f2i.poke(f2i.B)
        dut.io.frm.poke(frm.U)
        dut.io.valid.poke(true.B)
        dut.clock.step()
        dut.io.valid.poke(false.B)
        dut.clock.step()
        (dut.io.out.peek().litValue & mask) should be(expected & mask)
        for (byte <- 0 until 8 if ((mask >> (byte*8)) & 255) != 0) dut.io.exc(byte).expect(flags.U)
      }
      val halves = Seq(0x3c00, 0xc000, 0x4200, 0xc400).map(BigInt(_)) // 1,-2,3,-4
      val ints = Seq(1, -2, 3, -4).map(BigInt(_))
      run(pack(halves,16), 1, false,false,false,true, pack(ints,16))
      run(pack(ints,16), 1, false,false,true,false, pack(halves,16))
      run(pack(Seq(single(17),single(-23)),32), 2,false,false,false,true, pack(Seq(17,-23).map(BigInt(_)),32))
      run(pack(Seq(17,-23).map(BigInt(_)),32), 2,false,false,true,false, pack(Seq(single(17),single(-23)),32))
      run(pack(halves,16), 1,true,false,false,true, pack(Seq(1,3).map(BigInt(_)),32))
      run(pack(ints,16), 1,true,false,true,false, pack(Seq(single(1),single(3)),32))
      run(pack(halves,16), 1,true,false,false,false, pack(Seq(single(1),single(3)),32))
      val lowHalves = BigInt("0000ffff0000ffff",16)
      run(pack(Seq(single(17),single(-23)),32), 2,false,true,false,true,
        pack(Seq(17,-23).map(BigInt(_)),32), lowHalves)
      run(pack(Seq(1,3).map(BigInt(_)),32), 2,false,true,true,false,
        pack(Seq(0x3c00,0x4200).map(BigInt(_)),32), lowHalves)
      run(pack(Seq(single(1),single(3)),32), 2,false,true,false,false,
        pack(Seq(0x3c00,0x4200).map(BigInt(_)),32), lowHalves)
      run(pack(ints,16), 0,true,false,true,false, pack(halves,16))
      run(pack(halves,16), 1,false,true,false,true, pack(ints,16), BigInt("00ff00ff00ff00ff",16))
      // RMM is encoded as 100 and must survive the three-bit rounding-mode port.
      run(pack(Seq(single(2.5f), single(-2.5f)),32), 2,false,false,false,true,
        pack(Seq(3,-3).map(BigInt(_)),32), frm = 4, flags = 1)
    }
  }

  for (segmented <- Seq(false, true)) {
    it should s"retain FP16, FP32 and FP16-to-FP32 FMA (segmented=$segmented)" in {
      test(if (segmented) new SegmentedFMAPipe(4) else new TandemFMAPipe(4))
        .withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
          dut.io.valid.poke(false.B)
          dut.io.frm.poke(0.U)
          dut.io.addsub.poke(false.B)
          dut.io.mul.poke(false.B)
          dut.io.op.poke(0.U)
          def run(a: Seq[BigInt], b: Seq[BigInt], c: Seq[BigInt], inEew: Int, outEew: Int,
              expected: Seq[BigInt]): Unit = {
            dut.io.a_eew.poke(inEew.U)
            dut.io.b_eew.poke(inEew.U)
            dut.io.c_eew.poke(outEew.U)
            dut.io.out_eew.poke(outEew.U)
            dut.io.a.poke(pack(a, 8 << inEew).U)
            dut.io.b.poke(pack(b, 8 << inEew).U)
            dut.io.c.poke(pack(c, 8 << outEew).U)
            dut.io.valid.poke(true.B)
            dut.clock.step()
            dut.io.valid.poke(false.B)
            dut.clock.step(2)
            dut.io.out.expect(pack(expected, 8 << outEew).U)
            dut.io.exc.foreach(_.expect(0.U))
            dut.clock.step()
          }
          run(Seq(single(1),single(3)), Seq(single(2),single(4)), Seq(single(5),single(6)), 2,2,
            Seq(single(7),single(18)))
          run(Seq(0x3c00,0x4000,0x4200,0x4400).map(BigInt(_)), Seq.fill(4)(BigInt(0x4000)),
            Seq.fill(4)(BigInt(0x3c00)),1,1,Seq(0x4200,0x4500,0x4700,0x4880).map(BigInt(_)))
          run(Seq(0x3c00,0,0x4200,0).map(BigInt(_)), Seq(0x4000,0,0x4400,0).map(BigInt(_)),
            Seq(single(5),single(6)),1,2,Seq(single(7),single(18)))
        }
    }
  }
}
