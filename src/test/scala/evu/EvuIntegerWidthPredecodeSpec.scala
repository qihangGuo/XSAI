package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.area.EvuFuMain
import xiangshan.evu.EvuParams
import xiangshan.evu.common._
import xiangshan.evu.exu._
import xiangshan.evu.insns._

/** Restore only the old input selection to compare the retiming independently
  * of the shared arithmetic. Unused predecoded registers disappear here.
  */
class EvuIntegerLegacySelection(implicit p: Parameters) extends IntegerPipe {
  adder_arr.io.in1 := Mux(rvs1_eew < vd_eew, narrow_vs1, in1_bytes)
  adder_arr.io.in2 := Mux(rvs2_eew < vd_eew, narrow_vs2, in2_bytes)
}

object EvuIntegerLegacyFactory extends FunctionalUnitFactory {
  def insns: Seq[VectorInstruction] = IntegerPipeFactory.insns
  def generate(implicit p: Parameters): FunctionalUnit = new EvuIntegerLegacySelection
}

class EvuIntegerWidthPredecodeHarness(implicit p: Parameters) extends Module {
  val current = Module(new ExecutionUnit(Seq(IntegerPipeFactory, BitwisePipeFactory), "integer_predecode"))
  val legacy = Module(new ExecutionUnit(Seq(EvuIntegerLegacyFactory, BitwisePipeFactory), "integer_legacy"))
  val io = IO(new Bundle {
    val issue = Flipped(Decoupled(new ExecuteMicroOpWithData(2)))
    val write = Output(Valid(new VectorWrite(p(VectorParamsKey).dLen)))
    val saturation = Output(Bool())
    val equivalent = Output(Bool())
  })
  for (unit <- Seq(current, legacy)) {
    unit.io.iss.valid := io.issue.valid
    unit.io.iss.bits := io.issue.bits
    unit.io.iter_write.ready := true.B
    unit.io.scalar_write.ready := true.B
    unit.io.shared_fp_req.ready := true.B
    unit.io.shared_fp_resp := 0.U.asTypeOf(unit.io.shared_fp_resp)
  }
  io.issue.ready := current.io.iss.ready && legacy.io.iss.ready
  io.write := current.io.pipe_write
  io.saturation := current.io.set_vxsat
  io.equivalent := current.io.iss.ready === legacy.io.iss.ready &&
    current.io.pipe_write.valid === legacy.io.pipe_write.valid &&
    (!current.io.pipe_write.valid || current.io.pipe_write.bits.asUInt === legacy.io.pipe_write.bits.asUInt) &&
    current.io.set_vxsat === legacy.io.set_vxsat &&
    current.io.completed.asUInt === legacy.io.completed.asUInt &&
    current.io.tagged_vxsat.asUInt === legacy.io.tagged_vxsat.asUInt
}

class EvuIntegerWidthPredecodeSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val base = Parameters.empty.alterPartial {
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 1
    case freechips.rocketchip.rocket.PgLevels => 3
  }
  private val ep = EvuParams(enable = true)
  private implicit val parameters: Parameters = EvuVectorParameters(base, ep.vlenBits, ep.dlenBits,
    ep.mlenBits, EvuFuMain.vectorParams(ep))
  private val dataBits = ep.dlenBits
  private val dataBytes = dataBits / 8
  private val dataMask = (BigInt(1) << dataBits) - 1
  private val byteMask = (BigInt(1) << dataBytes) - 1

  private def clear(data: Data): Unit = data match {
    case value: Bool => value.poke(false.B)
    case value: UInt => value.poke(0.U)
    case value: SInt => value.poke(0.S)
    case values: Vec[_] => values.foreach(clear)
    case bundle: Bundle => bundle.elements.values.foreach(clear)
  }
  private def signed(value: BigInt, bits: Int): BigInt =
    if (value.testBit(bits - 1)) value - (BigInt(1) << bits) else value
  private def pack(values: Seq[BigInt], bits: Int): BigInt = {
    val mask = (BigInt(1) << bits) - 1
    values.zipWithIndex.foldLeft(BigInt(0)) { case (result, (value, index)) =>
      result | ((value & mask) << (index * bits))
    }
  }

  private case class Operation(insn: VectorInstruction, fu: Int, source1: BigInt, source2: BigInt,
      rmask: BigInt, wmask: BigInt, upper: Boolean, vm: Boolean, rm: Int, tag: Int) {
    def field(f: InstructionField): Int = insn.lookup(f).value.toInt
    val sew = field(SEW)
    val outEew = sew + field(Wide2VD)
    val vs1Eew = sew
    val vs2Eew = sew + field(Wide2VS2)
    val depth = if (fu == 0 && field(UsesSat) != 0) 2 else 1
    val index = if (upper) dataBytes >> outEew else 0
    val outputMask = pack((0 until dataBytes).map(i => if (wmask.testBit(i)) BigInt(255) else BigInt(0)), 8)

    // Independent element arithmetic for add/sub/ADC/SBC and saturating/widening
    // operations. The remaining instructions are checked against the old FU.
    def arithmetic: Option[(BigInt, Boolean)] = {
      if (fu != 0 || insn.lookup(DoSub).mask == 0 || field(Averaging) != 0 ||
          field(WritesAsMask) != 0 || field(UsesCmp) != 0 || field(UsesMinMax) != 0 ||
          field(UsesMerge) != 0 || field(UsesNarrowingSext) != 0) return None
      val width = 8 << outEew
      val count = dataBits / width
      val elementMask = (BigInt(1) << width) - 1
      val extendSigned = field(WideningSext) != 0
      def operand(source: BigInt, eew: Int, index: Int): BigInt = {
        val bits = 8 << eew
        val offset = index + (if (upper && eew < outEew) count else 0)
        val value = (source >> (offset * bits)) & ((BigInt(1) << bits) - 1)
        if (eew < outEew && extendSigned) signed(value, bits) else value
      }
      var saturates = false
      val result = (0 until count).map { i =>
        val first = operand(source1, vs1Eew, i) & elementMask
        val second = operand(source2, vs2Eew, i) & elementMask
        val (left, right) = if (field(Swap12) != 0) (first, second) else (second, first)
        val satSigned = field(UsesSat) != 0 && (field(F6) & 1) != 0
        val a = if (satSigned) signed(left, width) else left
        val b = if (satSigned) signed(right, width) else right
        val carryEnabled = field(CarryIn) != 0 && (!vm || field(AlwaysCarryIn) != 0)
        val carry = if (carryEnabled && rmask.testBit(i)) 1 else 0
        val raw = if (field(DoSub) != 0) a - b - carry else a + b + carry
        if (field(UsesSat) == 0) raw & elementMask else {
          val minimum = if (satSigned) -(BigInt(1) << (width - 1)) else BigInt(0)
          val maximum = if (satSigned) (BigInt(1) << (width - 1)) - 1 else elementMask
          val clipped = raw.max(minimum).min(maximum)
          val active = (0 until (width / 8)).exists(j => wmask.testBit(i * (width / 8) + j))
          saturates ||= clipped != raw && active
          clipped & elementMask
        }
      }
      Some((pack(result, width), saturates))
    }
  }

  behavior of "IntegerPipe widening control predecode"

  it should "preserve every supported instruction across widths, bubbles, other FUs and mixed latencies" in {
    test(new EvuIntegerWidthPredecodeHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val random = new scala.util.Random(0x2500)
      val instructions = IntegerPipeFactory.insns.map(_ -> 0) ++
        BitwisePipeFactory.insns.filter(_.lookup(WritesAsMask).value == 0).map(_ -> 1)
      var tag = 0
      val operations = (0 until 4).flatMap { trial =>
        random.shuffle(instructions).map { case (insn, fu) =>
          val width = 8 << insn.lookup(SEW).value.toInt
          val edgeValues = Seq(BigInt(0), BigInt(1), (BigInt(1) << (width - 1)) - 1,
            BigInt(1) << (width - 1), (BigInt(1) << width) - 1)
          def source(shift: Int): BigInt = if (trial < 2)
            pack(Seq.tabulate(dataBits / width)(i => edgeValues((i + shift + trial) % edgeValues.size)), width)
          else BigInt(dataBits, random)
          val op = Operation(insn, fu, source(0), source(2), BigInt(dataBytes, random),
            if (trial == 0) byteMask else BigInt(dataBytes, random), trial % 2 != 0,
            trial % 2 == 0, trial, tag)
          tag += 1
          op
        }
      }
      val pending = scala.collection.mutable.Map.empty[Int, Operation]
      var next = 0
      var checked = 0
      var arithmeticChecked = 0
      var cycle = 0
      dut.io.issue.valid.poke(false.B)
      clear(dut.io.issue.bits)
      dut.clock.step(2)
      while (next < operations.size || pending.nonEmpty) {
        withClue(s"cycle=$cycle: ") {
          dut.io.equivalent.expect(true.B)
          dut.io.write.valid.expect(pending.contains(cycle).B)
          pending.remove(cycle).foreach { op =>
            dut.io.write.bits.eg.expect((op.tag % 256).U)
            if (op.fu == 0 && op.field(WritesAsMask) == 0)
              dut.io.write.bits.mask.expect(op.outputMask.U)
            op.arithmetic.foreach { case (expected, saturates) =>
              withClue(s"tag=${op.tag}, f3=${op.field(F3)}, f6=${op.field(F6)}, sew=${op.sew}, upper=${op.upper}: ") {
                dut.io.write.bits.data.expect((expected & dataMask).U)
                dut.io.saturation.expect(saturates.B)
              }
              arithmeticChecked += 1
            }
            checked += 1
          }
        }
        clear(dut.io.issue.bits)
        // Toggle unused issue inputs during bubbles: RegEnable must retain the
        // control for the previous issue rather than consume these new widths.
        dut.io.issue.bits.rvs1_eew.poke(random.nextInt(3).U)
        dut.io.issue.bits.vd_eew.poke(random.nextInt(3).U)
        val issue = next < operations.size && cycle % 7 != 3 &&
          !pending.contains(cycle + operations(next).depth)
        dut.io.issue.valid.poke(issue.B)
        if (issue) {
          val op = operations(next)
          val bits = dut.io.issue.bits
          bits.fu_sel.poke((1 << op.fu).U)
          bits.funct3.poke(op.field(F3).U)
          bits.funct6.poke(op.field(F6).U)
          bits.rs1.poke(op.field(RS1).U)
          bits.rvs1_eew.poke(op.vs1Eew.U)
          bits.rvs2_eew.poke(op.vs2Eew.U)
          bits.rvd_eew.poke(op.outEew.U)
          bits.vd_eew.poke(op.outEew.U)
          bits.sew.poke(op.sew.U)
          bits.rvs1_data.poke(op.source1.U)
          bits.rvs2_data.poke(op.source2.U)
          bits.rmask.poke(op.rmask.U)
          bits.wmask.poke(op.wmask.U)
          bits.full_tail_mask.poke(dataMask.U)
          bits.eidx_mask.poke(byteMask.U)
          bits.eidx.poke(op.index.U)
          bits.vm.poke(op.vm.B)
          bits.rm.poke(op.rm.U)
          bits.pipe_depth.poke((op.depth - 1).U)
          bits.wvd_eg.poke((op.tag % 256).U)
          bits.vat.poke((op.tag % 32).U)
          bits.tail.poke(true.B)
          dut.io.issue.ready.expect(true.B)
          pending(cycle + op.depth) = op
          next += 1
        }
        dut.clock.step()
        cycle += 1
      }
      dut.io.equivalent.expect(true.B)
      checked shouldBe operations.size
      arithmeticChecked should be > 100
      info(s"Checked $checked issues across $cycle cycles; $arithmeticChecked independent arithmetic results")
    }
  }
}
