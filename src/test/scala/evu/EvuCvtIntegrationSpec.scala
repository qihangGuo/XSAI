package evu

import chisel3._
import chisel3.util._
import chiseltest._
import evu.cvtref.{BF16Reference, LowpReference, NativeConvertReference}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.TileVisibilityNodeKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._
import xiangshan.evu.backend.VrfPerf

import scala.collection.mutable

/** External instruction decoder and the real Saturn backend, VRF and memory path.
  * PC supplies a test job identity after decoding; no arithmetic or VRF access is
  * implemented by this shell. Completion is backend completion, not scalar ROB retirement.
  */
class EvuCvtIntegrationShell(val ep: EvuParams)(implicit p: Parameters) extends LazyModule {
  val visibility = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("cvt-test")))))
  val sink = TLManagerNode(Seq(TLSlavePortParameters.v1(
    Seq(TLSlaveParameters.v1(address = Seq(AddressSet(0, 0xffffff)),
      supportsGet = TransferSizes(1, 64), supportsPutFull = TransferSizes(1, 64))), beatBytes = 64)))
  sink := visibility
  lazy val module = new EvuCvtIntegrationShellImp(this)
}

class EvuCvtIntegrationShellImp(outer: EvuCvtIntegrationShell)(implicit p: Parameters)
    extends LazyModuleImp(outer) {
  val ep = outer.ep
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new EvuDispatchRequest(ep)))
    val completion = Decoupled(new EvuCompletion(ep))
    val memory = new EvuMemoryIO(ep)
    val flush = Input(Bool())
    val vstart = Input(UInt(log2Ceil(ep.vlenBits + 1).W))
    val lmul = Input(UInt(3.W))
    val vta = Input(Bool())
    val vill = Input(Bool())
    val busy = Output(Bool())
    val vrf = Output(Vec(ep.vectorRegs * ep.vlenBits / ep.dlenBits, UInt(ep.dlenBits.W)))
    val perf = Output(new VrfPerf)
  })
  val dispatch = Module(new EvuDispatchAdapter(ep))
  val backend = Module(new EvuVectorBackend(ep)(p.alterPartial {
    case TileVisibilityNodeKey => outer.visibility
  }))
  dispatch.io.in <> io.command
  dispatch.io.flush := io.flush
  backend.io.command <> dispatch.io.out
  backend.io.command.bits.jobId := dispatch.io.out.bits.pc(log2Ceil(ep.commandDepth) + 1, 2)
  io.completion <> backend.io.completion
  io.memory <> backend.io.memory
  io.vrf := backend.io.debugVrf.get
  io.perf := backend.io.vrfPerf
  io.busy := backend.io.busy
  backend.io.flush := io.flush
  backend.io.vta := io.vta
  backend.io.vma := false.B
  backend.io.vlmulSign := io.lmul(2)
  backend.io.vlmulMag := Mux(io.lmul(2), (8.U - io.lmul)(1, 0), io.lmul(1, 0))
  backend.io.vstart := io.vstart
  backend.io.vill := io.vill
  val bus = outer.visibility.out.head._1
  bus.a.valid := false.B
  bus.a.bits := 0.U.asTypeOf(bus.a.bits)
  bus.d.ready := true.B
  val manager = outer.sink.in.head._1
  manager.a.ready := true.B
  manager.d.valid := false.B
  manager.d.bits := 0.U.asTypeOf(manager.d.bits)
}

class EvuCvtIntegrationSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU external CVT instructions through the production vector backend"

  // Independent architectural table: do not derive expected routing from DUT descriptors.
  private case class Op(name: String, f7: Int, format: Int, decode: Boolean,
      scaled: Boolean = false, bf16: Boolean = false) {
    val lowBits: Int = if (bf16) 16 else if (format == LowpReference.E2M1) 4 else 8
    val sourceBits: Int = if (decode) lowBits else 32
    val destinationBits: Int = if (decode) 32 else lowBits
    def result(src: BigInt, factor: Int): (BigInt, Int) = {
      if (bf16) {
        val r = if (decode) BF16Reference.widen(src) else BF16Reference.narrow(src, 0)
        val second = NativeConvertReference.convert(src,
          if (decode) NativeConvertReference.BF16 else NativeConvertReference.F32,
          if (decode) NativeConvertReference.F32 else NativeConvertReference.BF16)
        assert((r.bits, r.flags) == ((second.bits, second.flags)), s"BF16 reference disagreement $src")
        (r.bits, r.flags)
      } else {
        val r = LowpReference.convert(src, format, decode, scaled, factor, 0)
        (r.bits, r.flags)
      }
    }
  }
  private val ops = Seq(
    Op("i8.f32", 0x70, LowpReference.I8, false),
    Op("e4m3.f32", 0x71, LowpReference.E4M3, false),
    Op("e5m2.f32", 0x72, LowpReference.E5M2, false),
    Op("f32.e4m3", 0x73, LowpReference.E4M3, true),
    Op("mxfp8.f32", 0x74, LowpReference.E4M3, false, scaled = true),
    Op("mxfp4.f32", 0x75, LowpReference.E2M1, false, scaled = true),
    Op("u8.f32", 0x27, LowpReference.U8, false),
    Op("f32.i8", 0x28, LowpReference.I8, true),
    Op("f32.u8", 0x29, LowpReference.U8, true),
    Op("e2m1.f32", 0x2a, LowpReference.E2M1, false),
    Op("f32.e5m2", 0x2b, LowpReference.E5M2, true),
    Op("f32.e2m1", 0x2c, LowpReference.E2M1, true),
    Op("bf16.f32", 0x2d, -1, false, bf16 = true),
    Op("f32.bf16", 0x2e, -1, true, bf16 = true),
    Op("mxe5m2.f32", 0x78, LowpReference.E5M2, false, scaled = true),
    Op("f32.mxe4m3", 0x79, LowpReference.E4M3, true, scaled = true),
    Op("f32.mxe5m2", 0x7a, LowpReference.E5M2, true, scaled = true),
    Op("f32.mxfp4", 0x7b, LowpReference.E2M1, true, scaled = true))
  private def op(f7: Int): Op = ops.find(_.f7 == f7).get
  private def mask(bits: Int): BigInt = (BigInt(1) << bits) - 1
  private def pack(values: Seq[BigInt], bits: Int): BigInt =
    values.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (value, i)) => acc | ((value & mask(bits)) << (bits * i)) }
  private def fp(value: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(value).toLong & 0xffffffffL)

  it should "check all 18 external opcodes, packed tails, flags, dependencies and bank placement" in {
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    val forwarding = !sys.env.get("EVU_CVT_DISABLE_FORWARDING").contains("1")
    val ep = EvuParams(enable = true, enableDifftest = true,
      extendedResultForwarding = forwarding, shortResultForwarding = forwarding)
    test(LazyModule(new EvuCvtIntegrationShell(ep)).module)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.clock.setTimeout(200000)
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
        dut.io.lmul.poke(0.U)
        dut.io.vta.poke(false.B)
        dut.io.vill.poke(false.B)

        case class Completion(flags: Int, illegal: Boolean, cause: Int, saturated: Boolean)
        val memory = mutable.Map.empty[BigInt, Int]
        val responses = mutable.ArrayBuffer.empty[(BigInt, BigInt)]
        var response = Option.empty[(BigInt, BigInt)]
        val completed = mutable.Map.empty[Int, Completion]
        val completionOrder = mutable.ArrayBuffer.empty[Int]
        var cycles = 0L
        var commands = 0
        var comparisons = 0L
        var scenarios = 0
        var requests = 0L
        var stallMemory = true
        var nextBase = BigInt(0x1000)
        def tick(): Unit = {
          dut.io.memory.req.ready.poke((!stallMemory || cycles % 7 != 3).B)
          if (response.isEmpty && responses.nonEmpty && (!stallMemory || cycles % 5 != 2)) {
            // Reorder independent memory responses while keeping each accepted
            // response stable until ready. Tags, rather than request order, identify data.
            val index = if (stallMemory && cycles % 3 == 0) responses.size - 1 else 0
            response = Some(responses.remove(index))
          }
          dut.io.memory.resp.valid.poke(response.nonEmpty.B)
          response.foreach { case (tag, data) =>
            dut.io.memory.resp.bits.tag.poke(tag.U)
            dut.io.memory.resp.bits.data.poke(data.U)
          }
          val responseTaken = response.nonEmpty && dut.io.memory.resp.ready.peek().litToBoolean
          if (dut.io.memory.req.valid.peek().litToBoolean && dut.io.memory.req.ready.peek().litToBoolean) {
            val r = dut.io.memory.req.bits
            val addr = r.addr.peek().litValue
            val write = r.write.peek().litToBoolean
            val byteMask = r.mask.peek().litValue
            val data = r.data.peek().litValue
            if (write) for (i <- 0 until ep.mlenBytes if byteMask.testBit(i))
              memory(addr + i) = ((data >> (8 * i)) & 255).toInt
            val result = (0 until ep.mlenBytes).foldLeft(BigInt(0)) { (bits, i) =>
              bits | (BigInt(memory.getOrElse(addr + i, 0)) << (8 * i))
            }
            responses += ((r.tag.peek().litValue, if (write) BigInt(0) else result))
            requests += 1
          }
          if (dut.io.completion.valid.peek().litToBoolean && dut.io.completion.ready.peek().litToBoolean) {
            val c = dut.io.completion.bits
            val job = c.jobId.peek().litValue.toInt
            assert(!completed.contains(job), s"duplicate completion job=$job cycle=$cycles")
            completed(job) = Completion(c.fflags.peek().litValue.toInt,
              c.exception.peek().litToBoolean, c.cause.peek().litValue.toInt, c.vxsat.peek().litToBoolean)
            completionOrder += job
          }
          dut.clock.step()
          cycles += 1
          if (responseTaken) response = None
        }
        def until(condition: => Boolean, label: String): Unit = {
          var waited = 0
          while (!condition && waited < 2000) { tick(); waited += 1 }
          assert(condition, s"timeout: $label at cycle $cycles")
        }
        def send(f7: Int, vd: Int, vs2: Int, vl: Int, vs1: Int = 0, job: Int = 0,
            sew: Int = 2, base: BigInt = 0, aux: Int = 0): Unit = {
          completed.remove(job)
          val c = dut.io.command.bits
          c.instr.poke(((BigInt(f7) << 25) | (BigInt(vs2) << 20) |
            (BigInt(vs1) << 15) | (BigInt(sew) << 12) | (BigInt(vd) << 7) | 0x0b).U)
          c.pc.poke((BigInt(0x80000000L) + 4 * job).U)
          c.config.valid.poke(false.B)
          c.config.configured.poke(false.B)
          c.config.vl.poke(vl.U)
          c.config.vtype.poke((sew << 3).U)
          c.config.vill.poke(false.B)
          c.base.poke(base.U)
          c.vl.poke(vl.U)
          c.length.poke(ep.vlenBytes.U)
          c.aux.poke(aux.U)
          c.stride.poke(0.S)
          c.chain.poke(true.B)
          c.pageMap.enabled.poke(false.B)
          c.pageMap.base.poke(0.U)
          c.pageMap.length.poke(0.U)
          c.pageMap.ppn.foreach(_.poke(0.U))
          dut.io.command.valid.poke(true.B)
          until(dut.io.command.ready.peek().litToBoolean, s"accept f7=$f7 job=$job")
          tick()
          dut.io.command.valid.poke(false.B)
          commands += 1
        }
        def waitFor(job: Int = 0, flags: Int = 0, illegal: Boolean = false): Unit = {
          until(completed.contains(job), s"complete job=$job")
          withClue(s"job=$job cycle=$cycles: ") {
            completed(job) shouldBe Completion(flags, illegal, if (illegal) 1 else 0, false)
          }
        }
        def load(reg: Int, bits: BigInt, job: Int = 0, wait: Boolean = true): Unit = {
          val base = nextBase
          nextBase += ep.vlenBytes
          for (i <- 0 until ep.vlenBytes) memory(base + i) = ((bits >> (8 * i)) & 255).toInt
          send(0x10, reg, 0, ep.vlenBytes, job = job, sew = 0, base = base)
          if (wait) waitFor(job)
        }
        def checkReg(reg: Int, expected: BigInt, label: String): Unit = {
          val base = nextBase
          nextBase += ep.vlenBytes
          for (i <- 0 until ep.vlenBytes) memory(base + i) = 0x69
          send(0x11, reg, 0, ep.vlenBytes, sew = 0, base = base)
          waitFor()
          for (i <- 0 until ep.vlenBytes) {
            assert(memory(base + i) == ((expected >> (8 * i)) & 255).toInt,
              s"$label byte=$i got=${memory(base + i).toHexString} want=${((expected >> (8 * i)) & 255).toString(16)}")
            comparisons += 1
          }
          for (eg <- 0 until ep.vlenBits / ep.dlenBits)
            dut.io.vrf(reg * (ep.vlenBits / ep.dlenBits) + eg)
              .expect(((expected >> (eg * ep.dlenBits)) & mask(ep.dlenBits)).U)
        }
        val sentinel = pack((0 until ep.vlenBytes).map(i => BigInt((i * 73 + 0xa5) & 255)), 8)
        val floatAnchors = Seq("00000000", "80000000", "3f800000", "bf800000", "3fc00000",
          "3f808000", "007f8000", "00000001", "7f7fffff", "7f800000", "ff800000",
          "7fc00000", "7f800001", "43000000", "437f0000", "c3000000", "3e800000", "40c00000")
          .map(BigInt(_, 16))
        val rng = new scala.util.Random(0xc07e18L)
        def inputs(o: Op, salt: Int): Seq[BigInt] = (0 until 128).map { i =>
          if (!o.decode) {
            if (i < floatAnchors.size) floatAnchors((i + salt) % floatAnchors.size)
            else BigInt(32, rng)
          } else if (o.bf16) {
            val anchors = Seq(0, 0x8000, 0x3f80, 0x007f, 0x0080, 0x7f80, 0xff80, 0x7fc0, 0x7f81, 0x7f7f)
            if (i < anchors.size) BigInt(anchors((i + salt) % anchors.size)) else BigInt(16, rng)
          } else BigInt((i * 29 + salt * 17) & ((1 << o.sourceBits) - 1))
        }
        def expected(o: Op, src: Seq[BigInt], vl: Int, factor: Int, old: BigInt = sentinel): (BigInt, Int) = {
          val values = src.take(vl).map(o.result(_, factor))
          val bytes = (vl * o.destinationBits + 7) / 8
          val packed = pack(values.map(_._1), o.destinationBits)
          ((old & (mask(ep.vlenBits) ^ mask(bytes * 8))) | packed,
            values.foldLeft(0)((flags, r) => flags | r._2))
        }
        def runCase(o: Op, vl: Int, factor: Int, salt: Int): Unit = {
          val src = inputs(o, salt)
          load(1, pack(src, o.sourceBits))
          load(2, sentinel)
          if (o.scaled) load(6, (sentinel & ~BigInt(255)) | factor)
          val (want, flags) = expected(o, src, vl, factor)
          dut.io.vta.poke((salt % 2 != 0).B)
          send(o.f7, 2, 1, vl, vs1 = if (o.scaled) 6 else 0)
          waitFor(flags = flags)
          checkReg(2, want, s"${o.name} VL=$vl scale=$factor")
          scenarios += 1
        }

        BF16Reference.narrow(BigInt("007f8000", 16), 0) shouldBe BF16Reference.Result(BigInt(0x80), 3)
        LowpReference.convert(fp(1.5f), LowpReference.I8, decode = false).bits shouldBe BigInt(2)
        val tails = Seq(0, 1, 15, 16, 17, 127, 128)
        val factors = Seq(0, 1, 120, 126, 127, 128, 254, 255)
        for ((o, index) <- ops.zipWithIndex; (vl, tail) <- tails.zipWithIndex)
          runCase(o, vl, if (o.scaled) factors(tail) else 127, index + tail)
        for (o <- ops.filter(_.scaled); factor <- factors)
          runCase(o, 17, factor, factor)
        dut.io.vta.poke(false.B)

        // RNE and inactive-lane flags are visible independently of aggregate
        // special-value stress. A signaling NaN immediately past VL must not set NV.
        for (o <- ops.filter(!_.decode)) {
          val src = Seq.fill(128)(fp(1.5f)).updated(1, BigInt("7f800001", 16))
          load(1, pack(src, 32)); load(2, sentinel); load(6, BigInt(127))
          val (want, flags) = expected(o, src, 1, 127)
          send(o.f7, 2, 1, 1, vs1 = if (o.scaled) 6 else 0)
          waitFor(flags = flags)
          checkReg(2, want, s"${o.name} inactive signaling NaN")
          scenarios += 1
        }

        // Four dependent operations span both physical lowp resources and the
        // one-cycle BF16 path, with no drain between external instructions.
        val chainInput = (0 until 128).map(i => fp((i % 17 - 8).toFloat / 4))
        load(1, pack(chainInput, 32))
        for (reg <- 8 to 11) load(reg, sentinel)
        var chainValues = chainInput
        val chain = Seq(op(0x71), op(0x73), op(0x2d), op(0x2e))
        val chainExpected = chain.zipWithIndex.map { case (o, index) =>
          val (want, flags) = expected(o, chainValues, 128, 127)
          chainValues = chainValues.map(o.result(_, 127)._1)
          send(o.f7, 8 + index, if (index == 0) 1 else 7 + index, 128, job = 4 + index)
          (8 + index, 4 + index, want, flags)
        }
        chainExpected.foreach { case (_, job, _, flags) => waitFor(job, flags) }
        chainExpected.foreach { case (reg, _, want, _) => checkReg(reg, want, "mixed latency RAW chain") }
        scenarios += 1

        // FP4 payloads occupy only 64 bytes at VL128. Submit the decoder
        // immediately after its producer so all eight logical beats must see
        // the newly packed bytes, including producer/consumer partial masks.
        for ((encode, decode, factor) <- Seq((op(0x2a), op(0x2c), 127), (op(0x75), op(0x7b), 128))) {
          load(12, sentinel); load(13, sentinel)
          if (encode.scaled) load(6, BigInt(factor))
          val payload = chainInput.map(encode.result(_, factor)._1)
          val encoded = expected(encode, chainInput, 128, factor)
          val decoded = expected(decode, payload, 128, factor)
          send(encode.f7, 12, 1, 128, vs1 = if (encode.scaled) 6 else 0, job = 4)
          send(decode.f7, 13, 12, 128, vs1 = if (decode.scaled) 6 else 0, job = 5)
          waitFor(4, encoded._2); waitFor(5, decoded._2)
          checkReg(12, encoded._1, s"${encode.name} unsynchronized FP4 RAW producer")
          checkReg(13, decoded._1, s"${decode.name} unsynchronized FP4 RAW consumer")
          scenarios += 1
        }

        // All 128 FP4 values reside in source EG0. The decoder must retain
        // that physical read intent through logical eidx112, even after eidx64.
        // A younger one-cycle BF16 writer uses another execution resource and
        // overwrites this payload register as soon as the WAR dependency allows.
        val oldFp4 = (0 until 128).map(i => BigInt((i * 7 + i / 16 + 3) & 15))
        val oldPayload = (sentinel & (mask(ep.vlenBits) ^ mask(512))) | pack(oldFp4, 4)
        val overwriteInput = (0 until 128).map(i => fp((100 + i).toFloat + 0.25f))
        load(3, pack(overwriteInput, 32))
        for ((decode, factor) <- Seq((op(0x2c), 127), (op(0x7b), 128))) {
          load(12, oldPayload); load(13, sentinel)
          if (decode.scaled) load(6, BigInt(factor))
          val decoded = expected(decode, oldFp4, 128, factor)
          val overwritten = expected(op(0x2d), overwriteInput, 128, 127, old = oldPayload)
          send(decode.f7, 13, 12, 128, vs1 = if (decode.scaled) 6 else 0, job = 4)
          send(0x2d, 12, 3, 128, job = 5)
          waitFor(4, decoded._2); waitFor(5, overwritten._2)
          checkReg(13, decoded._1, s"${decode.name} retains old FP4 payload through final beat")
          checkReg(12, overwritten._1, s"${decode.name} younger BF16 payload overwrite")
          scenarios += 1
        }

        // Produce the scale via CVT, consume it over eight FP32 groups, then
        // overwrite it while the consumer is still in flight (RAW and WAR).
        load(3, pack(Seq.fill(128)(fp(128f)), 32))
        load(4, pack(Seq.fill(128)(fp(255f)), 32))
        load(6, BigInt(127)); load(8, sentinel)
        send(0x27, 6, 3, 1, job = 4)
        send(0x74, 8, 1, 128, vs1 = 6, job = 5)
        send(0x27, 6, 4, 1, job = 6)
        waitFor(4); waitFor(5, expected(op(0x74), chainInput, 128, 128)._2); waitFor(6)
        checkReg(8, expected(op(0x74), chainInput, 128, 128)._1, "scale RAW/WAR")
        checkReg(6, BigInt(255), "younger scale overwrite")
        scenarios += 1

        // Register zero is a valid scale source. The remaining scale-register
        // bytes deliberately disagree, so an EG-relative factor read is detected.
        load(0, (sentinel & ~BigInt(255)) | 128)
        load(8, sentinel)
        send(0x75, 8, 1, 127, vs1 = 0)
        waitFor(flags = expected(op(0x75), chainInput, 127, 128)._2)
        checkReg(8, expected(op(0x75), chainInput, 127, 128)._1, "scale v0 and FP4 odd tail")
        scenarios += 1

        // Source operands may alias each other because both are read-only.
        // The low byte of this first FP32 value is the supplied UE8M0 factor.
        load(8, sentinel)
        val sharedFactor = (chainInput.head & 255).toInt
        send(0x74, 8, 1, 17, vs1 = 1)
        waitFor(flags = expected(op(0x74), chainInput, 17, sharedFactor)._2)
        checkReg(8, expected(op(0x74), chainInput, 17, sharedFactor)._1, "scale and payload source alias")
        scenarios += 1

        // A held completion cannot lose flags or publish a duplicate when a
        // one-cycle BF16 command completes adjacent to a two-cycle generic command.
        load(8, sentinel); load(9, sentinel)
        dut.io.completion.ready.poke(false.B)
        send(0x71, 8, 1, 17, job = 4)
        send(0x2d, 9, 1, 17, job = 5)
        until(dut.io.completion.valid.peek().litToBoolean, "held mixed-latency completion")
        val held = dut.io.completion.bits.peek()
        for (_ <- 0 until 12) { tick(); dut.io.completion.valid.expect(true.B); dut.io.completion.bits.expect(held) }
        dut.io.completion.ready.poke(true.B)
        waitFor(4, expected(op(0x71), chainInput, 17, 127)._2)
        waitFor(5, expected(op(0x2d), chainInput, 17, 127)._2)
        checkReg(8, expected(op(0x71), chainInput, 17, 127)._1, "generic held completion")
        checkReg(9, expected(op(0x2d), chainInput, 17, 127)._1, "BF16 held completion")
        scenarios += 1

        // Upstream PACK borrows the MX numeric core after its FP32 multiply.
        // Interleave both MX directions and BF16 without draining between jobs.
        // All products here are exact: 1.0 * 1.0 -> E2M1 code 2 (0x22/byte).
        val ones = Seq.fill(128)(fp(1f))
        load(1, pack(ones, 32)); load(6, BigInt(127))
        load(12, pack(Seq.fill(16)(BigInt("bf800000", 16)), 32))
        for (reg <- Seq(8, 9, 10, 11)) load(reg, sentinel)
        for (slot <- 0 until 8) {
          send(0x77, 8, 1, 16, job = 1, base = 12 | (slot << 11))
          send(0x78, 9, 1, 16, vs1 = 6, job = 2)
          send(0x7a, 10, 9, 16, vs1 = 6, job = 3)
          send(0x2d, 11, 1, 16, job = 4)
          for (job <- 1 to 4) waitFor(job)
        }
        val packResult = (sentinel & ~mask(512)) | pack(Seq.fill(64)(BigInt(0x22)), 8)
        checkReg(8, packResult, "PACK/MX shared provider preserves eight slots")
        checkReg(9, expected(op(0x78), ones, 16, 127)._1, "MX encode adjacent to PACK")
        checkReg(10, (sentinel & ~mask(512)) | pack(ones.take(16), 32), "MX decode adjacent to PACK")
        checkReg(11, expected(op(0x2d), ones, 16, 127)._1, "BF16 adjacent to PACK")
        scenarios += 1
        // Restore the payload expected by following original cases.
        load(1, pack(chainInput, 32))

        // Invalid commands remain illegal even at VL=0 and preserve all bytes.
        load(2, sentinel)
        for (o <- ops; vl <- Seq(0, 1)) {
          send(o.f7, 2, 2, vl, vs1 = if (o.scaled) 6 else 0)
          waitFor(illegal = true)
          send(o.f7, 2, 1, vl, vs1 = if (o.scaled) 6 else 0, sew = 1)
          waitFor(illegal = true)
          send(o.f7, 2, 1, vl, vs1 = if (o.scaled) 2 else 3)
          waitFor(illegal = true)
        }
        for (aux <- Seq(1, 2, 32, 64)) {
          send(0x71, 2, 1, 0, aux = aux); waitFor(illegal = true)
        }
        dut.io.vstart.poke(1.U)
        send(0x71, 2, 1, 0); waitFor(illegal = true)
        dut.io.vstart.poke(0.U)
        dut.io.lmul.poke(1.U)
        send(0x71, 2, 1, 0); waitFor(illegal = true)
        dut.io.lmul.poke(0.U)
        send(0x71, 2, 1, 129); waitFor(illegal = true)
        checkReg(2, sentinel, "illegal commands preserve destination")

        // Compare identical scalar-scale traffic under two register placements.
        // Always-ready memory and isolated compute windows remove stimulus phase
        // from the result. This records actual measured stalls, not an assumed gain.
        stallMemory = false
        load(1, pack(chainInput, 32))
        val bankRows = Seq(4, 5).map { scaleReg =>
          load(scaleReg, BigInt(127)); load(8, sentinel)
          val start = cycles
          val conflicts = dut.io.perf.bankConflicts.peek().litValue
          val waits = dut.io.perf.operandWait.peek().litValue
          send(0x74, 8, 1, 128, vs1 = scaleReg)
          waitFor(flags = expected(op(0x74), chainInput, 128, 127)._2)
          val row = s"scaleReg=$scaleReg cycles=${cycles - start} bankConflicts=${dut.io.perf.bankConflicts.peek().litValue - conflicts} operandWait=${dut.io.perf.operandWait.peek().litValue - waits}"
          checkReg(8, expected(op(0x74), chainInput, 128, 127)._1, s"bank placement $scaleReg")
          row
        }
        bankRows.foreach(row => println(s"EVU_CVT_BANK $row"))
        completionOrder.size shouldBe commands

        // Flush resets the actual backend; cancelled jobs must not appear after
        // recovery. Reload all required architectural state following the reset.
        completed.clear()
        send(0x71, 8, 1, 128, job = 4)
        for (_ <- 0 until 4) tick()
        assert(!completed.contains(4), "flush target completed before cancellation")
        dut.io.flush.poke(true.B)
        tick()
        dut.io.flush.poke(false.B)
        completed.clear()
        for (_ <- 0 until 40) { tick(); assert(!completed.contains(4), "cancelled CVT completed after flush") }
        load(1, pack(chainInput, 32)); load(8, sentinel)
        send(0x71, 8, 1, 17, job = 4)
        waitFor(4, expected(op(0x71), chainInput, 17, 127)._2)
        checkReg(8, expected(op(0x71), chainInput, 17, 127)._1, "flush recovery")
        completionOrder.size shouldBe (commands - 1)
        assert(scenarios >= 180 && comparisons > 90000 && requests > 0)
        println(s"EVU_CVT_INTEGRATION_PASS operations=${ops.size} scenarios=$scenarios byteComparisons=$comparisons commands=$commands memoryRequests=$requests cycles=$cycles forwarding=$forwarding")
      }
  }
}
