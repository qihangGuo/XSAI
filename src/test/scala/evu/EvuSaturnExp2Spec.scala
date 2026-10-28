package evu

import chisel3._
import chisel3.util._
import chiseltest._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.TileVisibilityNodeKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

import scala.collection.mutable

class EvuSaturnExp2Shell(val ep: EvuParams)(implicit p: Parameters) extends LazyModule {
  val visibility = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("exp2-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(
    Seq(TLSlaveParameters.v1(address = Seq(AddressSet(0, 0xffff)),
      supportsGet = TransferSizes(1, 64), supportsPutFull = TransferSizes(1, 64))), beatBytes = 64)))
  sink := visibility
  lazy val module = new EvuSaturnExp2ShellImp(this)
}

class EvuSaturnExp2ShellImp(outer: EvuSaturnExp2Shell)(implicit p: Parameters) extends LazyModuleImp(outer) {
  val ep = outer.ep
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new EvuCommand(ep)))
    val completion = Decoupled(new EvuCompletion(ep))
    val memory = new EvuMemoryIO(ep)
    val vrf = Output(Vec(ep.vectorRegs * ep.vlenBits / ep.dlenBits, UInt(ep.dlenBits.W)))
    val flush = Input(Bool())
    val vstart = Input(UInt(log2Ceil(ep.vlenBits + 1).W))
    val cuteWrite = Output(Valid(new EvuCuteWrite(ep)))
  })
  val backend = Module(new EvuVectorBackend(ep)(p.alterPartial {
    case TileVisibilityNodeKey => outer.visibility
  }))
  backend.io.command <> io.command
  io.completion <> backend.io.completion
  io.memory <> backend.io.memory
  io.vrf := backend.io.debugVrf.get
  backend.io.flush := io.flush
  backend.io.vta := false.B
  backend.io.vma := false.B
  backend.io.vlmulSign := false.B
  backend.io.vlmulMag := 0.U
  backend.io.vstart := io.vstart
  backend.io.vill := false.B
  backend.io.cuteWrite.ready := true.B
  io.cuteWrite.valid := backend.io.cuteWrite.valid
  io.cuteWrite.bits := backend.io.cuteWrite.bits
  backend.io.matrixInput.valid := false.B
  backend.io.matrixInput.bits := 0.U.asTypeOf(backend.io.matrixInput.bits)
  backend.io.matrixOutput.ready := true.B
  val bus = outer.visibility.out.head._1
  bus.a.valid := false.B
  bus.a.bits := 0.U.asTypeOf(bus.a.bits)
  bus.d.ready := true.B
  val manager = outer.sink.in.head._1
  manager.a.ready := true.B
  manager.d.valid := false.B
  manager.d.bits := 0.U.asTypeOf(manager.d.bits)
}

class EvuSaturnExp2Spec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Exp2 through the Saturn FP issue queue"

  private def pack(values: Seq[BigInt], width: Int): BigInt =
    values.zipWithIndex.map { case (value, i) => value << (width * i) }.foldLeft(BigInt(0))(_ | _)
  private def single(value: Float): BigInt =
    BigInt(java.lang.Float.floatToRawIntBits(value).toLong & 0xffffffffL)

  it should "honor FP16/FP32 VL, mask, vstart, aliases, completion flags and recovery" in {
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    val ep = EvuParams(enable = true, enableDifftest = true, vlenBits = 512)
    test(LazyModule(new EvuSaturnExp2Shell(ep)).module)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.clock.setTimeout(10000)
        dut.io.command.valid.poke(false.B)
        dut.io.completion.ready.poke(true.B)
        dut.io.memory.req.ready.poke(true.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        dut.io.flush.poke(false.B)
        dut.io.vstart.poke(0.U)
        val memory = mutable.Map.empty[BigInt, BigInt]
        val responses = mutable.Queue.empty[(BigInt, BigInt)]
        val completed = mutable.Map.empty[Int, (Int, Boolean, Int)]
        var response = Option.empty[(BigInt, BigInt)]
        var nextBase = 0x1000
        def tick(): Unit = {
          if (response.isEmpty && responses.nonEmpty) response = Some(responses.dequeue())
          dut.io.memory.resp.valid.poke(response.nonEmpty.B)
          response.foreach { case (tag, data) =>
            dut.io.memory.resp.bits.tag.poke(tag.U)
            dut.io.memory.resp.bits.data.poke(data.U)
          }
          val responseTaken = response.nonEmpty && dut.io.memory.resp.ready.peek().litToBoolean
          if (dut.io.memory.req.valid.peek().litToBoolean) {
            dut.io.memory.req.bits.write.expect(false.B)
            val addr = dut.io.memory.req.bits.addr.peek().litValue
            responses.enqueue((dut.io.memory.req.bits.tag.peek().litValue, memory(addr)))
          }
          if (dut.io.completion.valid.peek().litToBoolean && dut.io.completion.ready.peek().litToBoolean) {
            val c = dut.io.completion.bits
            if (!c.exception.peek().litToBoolean)
              c.dirtyVs.expect(true.B)
            completed(c.jobId.peek().litValue.toInt) =
              (c.fflags.peek().litValue.toInt, c.exception.peek().litToBoolean, c.cause.peek().litValue.toInt)
          }
          dut.clock.step()
          if (responseTaken) response = None
        }
        def send(opcode: UInt, vd: Int, sew: Int, vl: Int, job: Int = 0,
            source: Int = 1, aux: Int = 0, base: Int = 0, source1: Int = 31): Unit = {
          completed.remove(job)
          val c = dut.io.command.bits
          c.opcode.poke(opcode)
          c.vd.poke(vd.U)
          c.vs1.poke(source1.U) // Exp2 must ignore the unused second source.
          c.vs2.poke(source.U)
          c.sew.poke(sew.U)
          c.vl.poke(vl.U)
          c.base.poke(base.U)
          c.stride.poke(0.S)
          c.length.poke(ep.vlenBytes.U)
          c.aux.poke(aux.U)
          c.write.poke(false.B)
          c.chain.poke(false.B)
          c.jobId.poke(job.U)
          c.epoch.poke(0.U)
          c.pageMap.enabled.poke(false.B)
          c.pageMap.base.poke(0.U)
          c.pageMap.length.poke(0.U)
          c.pageMap.ppn.foreach(_.poke(0.U))
          dut.io.command.valid.poke(true.B)
          var waited = 0
          while (!dut.io.command.ready.peek().litToBoolean && waited < 500) {
            tick(); waited += 1
          }
          dut.io.command.ready.expect(true.B)
          tick()
          dut.io.command.valid.poke(false.B)
        }
        def waitFor(job: Int = 0, flags: Int = 0, illegal: Boolean = false): Unit = {
          var waited = 0
          while (!completed.contains(job) && waited < 500) { tick(); waited += 1 }
          completed.get(job) should be(Some((flags, illegal, if (illegal) 1 else 0)))
        }
        def load(reg: Int, values: Seq[BigInt], sew: Int): Unit = {
          val bits = pack(values, 8 << sew)
          val mask = (BigInt(1) << ep.mlenBits) - 1
          for (offset <- 0 until ep.vlenBytes by ep.mlenBytes) {
            memory(BigInt(nextBase + offset)) = (bits >> (offset * 8)) & mask
          }
          send(EvuOpcode.Load, reg, sew, ep.vlenBytes >> sew, base = nextBase)
          waitFor()
          nextBase += 128
        }
        def expectReg(reg: Int, values: Seq[BigInt], sew: Int): Unit = {
          val bits = pack(values, 8 << sew)
          val mask = (BigInt(1) << ep.dlenBits) - 1
          for (chunk <- 0 until ep.vlenBits / ep.dlenBits) {
            dut.io.vrf(reg * (ep.vlenBits / ep.dlenBits) + chunk)
              .expect(((bits >> (ep.dlenBits * chunk)) & mask).U)
          }
        }

        for (sew <- Seq(1, 2)) {
          val count = ep.vlenBytes >> sew
          val halfInputs = Seq(0xc200, 0xc000, 0xbc00, 0, 0x3c00, 0x4000, 0x4200, 0x4400)
          val halfResults = Seq(0x3000, 0x3400, 0x3800, 0x3c00, 0x4000, 0x4400, 0x4800, 0x4c00)
          val inputs = (0 until count).map(i =>
            if (sew == 1) BigInt(halfInputs(i % 8)) else single((i % 8 - 3).toFloat))
          val results = (0 until count).map(i =>
            if (sew == 1) BigInt(halfResults(i % 8)) else single(math.pow(2, i % 8 - 3).toFloat))
          val sentinel = if (sew == 1) BigInt(0xa55a) else BigInt("a55aa55a", 16)
          val snan = if (sew == 1) BigInt(0x7c01) else BigInt("7f800001", 16)
          val maskedInputs = inputs.updated(0, snan).updated(4, snan).updated(count - 1, snan)
          load(1, maskedInputs, sew)
          load(2, Seq.fill(count)(sentinel), sew)
          val mask = (0 until count).filter(_ % 3 != 1).foldLeft(BigInt(0))((m, i) => m.setBit(i))
          load(0, (0 until ep.vlenBytes).map(i => (mask >> (8 * i)) & 255), 0)
          dut.io.vstart.poke(3.U)
          send(EvuOpcode.Exp2, 2, sew, count - 3, aux = 2)
          waitFor()
          expectReg(2, results.zipWithIndex.map { case (value, i) =>
            if (i >= 3 && i < count - 3 && mask.testBit(i)) value else sentinel
          }, sew)
          expectReg(1, maskedInputs, sew)
          dut.io.vstart.poke(0.U)

          load(1, inputs, sew)
          send(EvuOpcode.Exp2, 1, sew, count)
          waitFor()
          expectReg(1, results, sew)

          val overflow = if (sew == 1) BigInt(0x4c00) else single(128f)
          load(1, inputs.updated(0, overflow).updated(1, snan), sew)
          dut.io.completion.ready.poke(false.B)
          send(EvuOpcode.Exp2, 2, sew, count)
          // An illegal special command may complete while Exp2 is in flight.
          send(EvuOpcode.Invalid, 0, 2, 0, job = 1)
          var waited = 0
          while (!dut.io.completion.valid.peek().litToBoolean && waited < 500) { tick(); waited += 1 }
          dut.io.completion.valid.expect(true.B)
          for (_ <- 0 until 40) tick()
          val held = dut.io.completion.bits.peek()
          for (_ <- 0 until 5) {
            tick()
            dut.io.completion.valid.expect(true.B)
            dut.io.completion.bits.expect(held)
          }
          dut.io.completion.ready.poke(true.B)
          waitFor(flags = 21)
          waitFor(job = 1, illegal = true)
          val inf = if (sew == 1) BigInt(0x7c00) else BigInt("7f800000", 16)
          val qnan = if (sew == 1) BigInt(0x7e00) else BigInt("7fc00000", 16)
          expectReg(2, results.updated(0, inf).updated(1, qnan), sew)

          // Reuse the destination and job without retaining the earlier NV/OF/NX flags.
          load(1, inputs, sew)
          send(EvuOpcode.Exp2, 2, sew, count)
          waitFor()
          expectReg(2, results, sew)
          send(EvuOpcode.Exp2, 2, sew, count)
          send(EvuOpcode.FAdd, 3, sew, count, job = 1, source1 = 1)
          waitFor()
          waitFor(job = 1, illegal = true)
          expectReg(2, results, sew)
          val doubledHalves = Seq(0xc600, 0xc400, 0xc000, 0, 0x4000, 0x4400, 0x4600, 0x4800)
          expectReg(3, (0 until count).map(i =>
            if (sew == 1) BigInt(doubledHalves(i % 8)) else single((2 * (i % 8 - 3)).toFloat)), sew)
          send(EvuOpcode.Exp2, 2, sew, 0)
          waitFor()
          dut.io.vstart.poke(count.U)
          send(EvuOpcode.Exp2, 2, sew, count)
          waitFor()
          dut.io.vstart.poke(0.U)
          expectReg(2, results, sew)
        }
        send(EvuOpcode.Exp2, 2, 0, 8)
        waitFor(illegal = true)
        send(EvuOpcode.Exp2, 2, 3, 8)
        waitFor(illegal = true)

        send(EvuOpcode.Exp2, 2, 2, 16)
        dut.io.flush.poke(true.B)
        tick()
        dut.io.flush.poke(false.B)
        completed.clear()
        for (_ <- 0 until 30) {
          dut.io.completion.valid.expect(false.B)
          tick()
        }
        send(EvuOpcode.Exp2, 2, 2, 16)
        waitFor()
      }
  }
}
