package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu.EvuParams
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.area.{EvuAreaMain, EvuExuMain}
import xiangshan.evu.common._
import xiangshan.evu.exu._
import xiangshan.evu.insns._

class EvuSetupObservedCurrent(factories: Seq[FunctionalUnitFactory])(implicit p: Parameters)
    extends ExecutionUnit(factories, "setup_current") {
  private val inputs = Cat(pipe_fus.flatMap { case (fu, _) => fu.io.pipe.map(_.asUInt) })
  val observedPipes = IO(Output(UInt(inputs.getWidth.W)))
  observedPipes := inputs
}

class EvuSetupObservedBaseline(factories: Seq[FunctionalUnitFactory])(implicit p: Parameters)
    extends EvuExecutionUnitSetupBaseline(factories, "setup_baseline") {
  private val inputs = Cat(pipe_fus.flatMap { case (fu, _) => fu.io.pipe.map(_.asUInt) })
  val observedPipes = IO(Output(UInt(inputs.getWidth.W)))
  observedPipes := inputs
}

class EvuExecutionUnitSetupHarness(implicit p: Parameters) extends Module {
  private val factories = EvuExuMain.targets(p(VectorParamsKey)).find(_.name == "int").get.fus
  val current = Module(new EvuSetupObservedCurrent(factories))
  val baseline = Module(new EvuSetupObservedBaseline(factories))
  val io = IO(new Bundle {
    val issue = Flipped(Decoupled(new ExecuteMicroOpWithData(factories.size)))
    val scalarReady = Input(Bool())
    val equivalent = Output(Bool())
    val pipeInputsEqual = Output(Bool())
    val pipeWrite = Output(Bool())
    val accWrite = Output(Bool())
    val scalarValid = Output(Bool())
    val completed = Output(Bool())
  })
  for (unit <- Seq(current.io, baseline.io)) {
    unit.iss.valid := io.issue.valid
    unit.iss.bits := io.issue.bits
    unit.scalar_write.ready := io.scalarReady
    unit.iter_write.ready := true.B
    unit.shared_fp_req.ready := true.B
    unit.shared_fp_resp := 0.U.asTypeOf(unit.shared_fp_resp)
  }
  val a = current.io
  val b = baseline.io
  io.issue.ready := a.iss.ready
  io.pipeWrite := a.pipe_write.valid
  io.accWrite := a.acc_write.valid
  io.scalarValid := a.scalar_write.valid
  io.completed := a.completed.valid
  io.pipeInputsEqual := current.observedPipes === baseline.observedPipes
  val comparisons = Seq(
    a.iss.ready === b.iss.ready,
    a.busy === b.busy,
    a.pipe_write.valid === b.pipe_write.valid,
    !a.pipe_write.valid || a.pipe_write.bits.asUInt === b.pipe_write.bits.asUInt,
    a.acc_write.valid === b.acc_write.valid,
    !a.acc_write.valid || a.acc_write.bits.asUInt === b.acc_write.bits.asUInt,
    a.acc_write_slot === b.acc_write_slot,
    a.iter_write.valid === b.iter_write.valid,
    !a.iter_write.valid || a.iter_write.bits.asUInt === b.iter_write.bits.asUInt,
    a.pipeWriteVat === b.pipeWriteVat,
    a.pipeWriteForwardable === b.pipeWriteForwardable,
    a.accWriteVat === b.accWriteVat,
    a.iterWriteVat === b.iterWriteVat,
    a.iterWriteForwardable === b.iterWriteForwardable,
    a.scalar_write.valid === b.scalar_write.valid,
    !a.scalar_write.valid || a.scalar_write.bits.asUInt === b.scalar_write.bits.asUInt,
    a.completed.valid === b.completed.valid,
    !a.completed.valid || a.completed.bits === b.completed.bits,
    a.set_vxsat === b.set_vxsat,
    a.set_fflags.valid === b.set_fflags.valid,
    !a.set_fflags.valid || a.set_fflags.bits === b.set_fflags.bits,
    a.shared_fp_req.valid === b.shared_fp_req.valid
  ) ++ a.pipe_hazards.zip(b.pipe_hazards).flatMap { case (x, y) => Seq(
    x.valid === y.valid, !x.valid || x.bits.asUInt === y.bits.asUInt)
  } ++ a.tagged_vxsat.zip(b.tagged_vxsat).flatMap { case (x, y) => Seq(
    x.valid === y.valid, !x.valid || x.bits === y.bits)
  } ++ a.tagged_fflags.zip(b.tagged_fflags).flatMap { case (x, y) => Seq(
    x.valid === y.valid, !x.valid || x.bits.asUInt === y.bits.asUInt)
  }
  io.equivalent := comparisons.reduce(_ && _) && io.pipeInputsEqual
}

class EvuExecutionUnitSetupPredecodeSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val ep = EvuParams(enable = true, enableDifftest = false)
  private implicit val parameters: Parameters = EvuVectorParameters(EvuAreaMain.parameters,
    ep.vlenBits, ep.dlenBits, ep.mlenBits, EvuExuMain.vectorParams(ep))
  private val factories = EvuExuMain.targets(parameters(VectorParamsKey)).find(_.name == "int").get.fus
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

  private case class Operation(insn: VectorInstruction, fu: Int, trial: Int, tag: Int,
      first: BigInt, second: BigInt, old: BigInt, rmask: BigInt, wmask: BigInt,
      head: Boolean = true, tail: Option[Boolean] = None, index: Option[Int] = None) {
    def field(f: InstructionField): Int = insn.lookup(f).value.toInt
    val depth = field(PipelineStagesMinus1) + 1
    val sew = field(SEW)
    val outEew = sew + field(Wide2VD)
    val vs2Eew = sew + field(Wide2VS2)
  }

  behavior of "ExecutionUnit same-edge control predecode"

  it should "match the frozen seven-FU unit every cycle, including zeroed FU inputs and scalar backpressure" in {
    test(new EvuExecutionUnitSetupHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val random = new scala.util.Random(0x250017)
      val instructions = factories.zipWithIndex.flatMap { case (factory, fu) => factory.insns.map(_ -> fu) }
      def directedInstruction(fu: Int, instruction: VectorInstruction): VectorInstruction =
        factories(fu).insns.find { candidate =>
          Seq(F3, F6, RS1, RS2).forall(f => candidate.lookup(f).value == instruction.lookup(f).value) &&
            candidate.lookup(SEW).value == 0
        }.get
      val directed = Seq((5, COMPRESS.VV), (5, RGATHER_VX), (3, POPC), (3, FIRST)).flatMap {
        case (fu, instruction) => (0 until 3).map { part =>
          Operation(directedInstruction(fu, instruction), fu, 0, 10000 + fu * 10 + part,
            BigInt(0x55), BigInt(dataBits, random), BigInt(dataBits, random), byteMask, byteMask,
            head = part == 0, tail = Some(part == 2), index = Some(part * dataBytes))
        }
      }
      val operations = directed ++ (0 until 4).flatMap { trial =>
        random.shuffle(instructions).zipWithIndex.map { case ((insn, fu), index) =>
          def value(offset: Int): BigInt = if (trial < 2) {
            val width = 8 << insn.lookup(SEW).value.toInt
            val edges = Seq(BigInt(0), BigInt(1), (BigInt(1) << (width - 1)) - 1,
              BigInt(1) << (width - 1), (BigInt(1) << width) - 1)
            (0 until dataBits / width).foldLeft(BigInt(0)) { (result, i) =>
              result | (edges((i + offset + trial) % edges.size) << (i * width))
            }
          } else BigInt(dataBits, random)
          Operation(insn, fu, trial, trial * instructions.size + index, value(0), value(2), value(3),
            BigInt(dataBytes, random), if (trial == 0) byteMask else BigInt(dataBytes, random))
        }
      }
      val pending = scala.collection.mutable.Set.empty[Int]
      val issuedByFu = Array.fill(factories.size)(0)
      var next = 0
      var cycle = 0
      var resetDone = false
      var vectorWrites = 0
      var accWrites = 0
      var scalarTransfers = 0
      var scalarBlocked = 0
      var issueBlocked = 0
      var scalarCooldown = 0
      var drain = 0
      clear(dut.io.issue.bits)
      dut.io.issue.valid.poke(false.B)
      dut.io.scalarReady.poke(true.B)
      dut.clock.step(2)
      while (next < operations.size || pending.nonEmpty || drain < 20) {
        withClue(s"cycle=$cycle next=$next: ") {
          dut.io.pipeInputsEqual.expect(true.B)
          dut.io.equivalent.expect(true.B)
        }
        pending -= cycle
        if (dut.io.pipeWrite.peek().litToBoolean) vectorWrites += 1
        if (dut.io.accWrite.peek().litToBoolean) accWrites += 1
        val scalarValid = dut.io.scalarValid.peek().litToBoolean
        if (scalarValid && scalarCooldown == 0 && cycle % 3 != 0) scalarCooldown = 4
        val scalarReady = scalarCooldown == 0
        if (scalarCooldown > 0) scalarCooldown -= 1
        dut.io.scalarReady.poke(scalarReady.B)
        if (scalarValid && scalarReady) scalarTransfers += 1
        if (scalarValid && !scalarReady) scalarBlocked += 1
        clear(dut.io.issue.bits)
        dut.io.issue.bits.fu_sel.poke((1 << random.nextInt(factories.size)).U)
        dut.io.issue.bits.tail.poke(random.nextBoolean().B)
        dut.io.issue.bits.vd_eew.poke(random.nextInt(3).U)
        dut.io.issue.bits.rvs1_data.poke(BigInt(dataBits, random).U)
        val canIssue = next < operations.size && cycle % 9 != 4 &&
          !pending.contains(cycle + operations(next).depth) &&
          (operations(next).fu != 3 || pending.isEmpty)
        dut.io.issue.valid.poke(canIssue.B)
        var fired = false
        if (canIssue) {
          val op = operations(next)
          val bits = dut.io.issue.bits
          bits.fu_sel.poke((1 << op.fu).U)
          bits.funct3.poke(op.field(F3).U)
          bits.funct6.poke(op.field(F6).U)
          bits.rs1.poke(op.field(RS1).U)
          bits.rs2.poke(op.field(RS2).U)
          bits.rd.poke((op.tag % 32).U)
          bits.rvs1_eew.poke(op.sew.U)
          bits.rvs2_eew.poke(op.vs2Eew.U)
          bits.rvd_eew.poke(op.outEew.U)
          bits.vd_eew.poke(op.outEew.U)
          bits.sew.poke(op.sew.U)
          bits.rvs1_data.poke(op.first.U)
          bits.rvs2_data.poke(op.second.U)
          bits.rvd_data.poke(op.old.U)
          bits.rvm_data.poke((if (op.trial % 2 == 0) dataMask else op.old).U)
          bits.rmask.poke(op.rmask.U)
          bits.wmask.poke(op.wmask.U)
          bits.full_tail_mask.poke(dataMask.U)
          bits.eidx_mask.poke((if (op.trial == 0) byteMask else op.wmask).U)
          bits.eidx.poke(op.index.getOrElse(if (op.trial % 2 != 0) dataBytes >> op.outEew else 0).U)
          bits.vl.poke((2 * dataBytes >> op.outEew).U)
          bits.vm.poke((op.trial % 2 == 0).B)
          bits.use_wmask.poke((op.trial == 3).B)
          bits.rm.poke(op.trial.U)
          bits.pipe_depth.poke((op.depth - 1).U)
          bits.wvd_eg.poke((op.tag % 256).U)
          bits.vat.poke((op.tag % 32).U)
          bits.acc.poke((op.trial >= 2 && op.fu != 3).B)
          bits.acc_slot.poke((op.tag % 4).U)
          bits.head.poke(op.head.B)
          bits.tail.poke(op.tail.getOrElse(op.trial != 2 || op.fu == 3).B)
          if (dut.io.issue.ready.peek().litToBoolean) {
            fired = true
            pending += cycle + op.depth
            issuedByFu(op.fu) += 1
            next += 1
          } else issueBlocked += 1
        }
        if (!resetDone && next >= operations.size / 2 && pending.nonEmpty) {
          // Reset replaces this edge, so retry a provisionally accepted issue.
          if (fired) {
            next -= 1
            issuedByFu(operations(next).fu) -= 1
          }
          dut.io.issue.valid.poke(false.B)
          dut.reset.poke(true.B)
          dut.clock.step()
          dut.reset.poke(false.B)
          pending.clear()
          resetDone = true
        } else dut.clock.step()
        cycle += 1
        if (next == operations.size) drain += 1
        cycle should be < 15000
      }
      dut.io.equivalent.expect(true.B)
      issuedByFu.foreach(_ should be > 0)
      vectorWrites should be > 100
      accWrites should be > 100
      scalarTransfers should be > 0
      scalarBlocked should be > 0
      resetDone shouldBe true
      info(s"Checked ${issuedByFu.sum} issues across $cycle cycles; FU counts=${issuedByFu.mkString(",")}; " +
        s"vector=$vectorWrites acc=$accWrites scalar=$scalarTransfers scalarBlocked=$scalarBlocked issueBlocked=$issueBlocked; reset replay checked")
    }
  }
}
