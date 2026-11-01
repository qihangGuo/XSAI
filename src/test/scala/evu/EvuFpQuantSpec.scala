package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

/** FIRRTL interpreter tests; no Verilog generation or EMU execution. */
class EvuFpQuantSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private def pack(values: Seq[BigInt], width: Int): BigInt = values.zipWithIndex.foldLeft(BigInt(0)) {
    case (a, (v, i)) => a | ((v & ((BigInt(1) << width) - 1)) << (i * width))
  }
  private def raw(x: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(x).toLong & 0xffffffffL)
  behavior of "Attention FP32 quantization"

  it should "queue rows before CUTE drains and retain each row target and flags through the shared pipeline" in {
    test(new EvuSpecialSequencer(EvuParams(enable = true))).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      def clear(x: Data): Unit = x match {
        case b: Bool => b.poke(false.B)
        case u: UInt => u.poke(0.U)
        case s: SInt => s.poke(0.S)
        case v: Vec[_] => v.foreach(clear)
        case b: Bundle => b.elements.values.foreach(clear)
      }
      clear(dut.io.command.bits)
      clear(dut.io.matrix.in.bits)
      dut.io.command.valid.poke(false.B)
      dut.io.completion.ready.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.invalidate.poke(false.B)
      dut.io.matrix.in.valid.poke(false.B)
      dut.io.matrix.out.ready.poke(true.B)
      dut.io.vrf.read.foreach(_.ready.poke(true.B))
      dut.io.vrf.readData.foreach(_.poke(0.U))
      dut.io.cuteWrite.ready.poke(false.B)
      def sourceData(): Unit = {
        for (i <- 0 until 3) {
          val reg = dut.io.vrf.read(i).bits.addr.peek().litValue.toInt
          val value = reg match {
            case 1 => raw(0.5f)
            case 2 => raw(0.49f)
            case 3 => BigInt("7f800001", 16)
            case _ => raw(1f)
          }
          dut.io.vrf.readData(i).poke(pack(Seq.fill(8)(value), 32).U)
        }
      }
      for (job <- 0 until 4) {
        val c = dut.io.command.bits
        c.opcode.poke(EvuOpcode.FpQuantToCute)
        c.aux.poke(0x10.U)
        c.sew.poke(2.U)
        c.vd.poke(8.U)
        c.vs2.poke((job + 1).U)
        c.vl.poke((if (job == 3) 9 else 64).U)
        c.base.poke((((job + 5) << 2) | 2).U)
        c.pc.poke((0x80001000L + job * 4).U)
        c.jobId.poke(job.U)
        dut.io.command.valid.poke(true.B)
        dut.io.command.ready.expect(true.B)
        sourceData()
        dut.clock.step()
      }
      dut.io.command.valid.poke(false.B)
      val widths = Seq(8, 8, 8, 2)
      val bytes = Seq(1, 0, 127, 1)
      val flags = Seq(0, 1, 16, 1) // 1.0 + 0.5 truncates with NX too.
      var row = 0
      var chunk = 0
      var completed = 0
      var cycles = 0
      while ((completed < 4 || row < 4) && cycles < 250) {
        sourceData()
        val writeReady = cycles % 4 != 0
        val completionReady = cycles > 90 && cycles % 3 != 0
        dut.io.cuteWrite.ready.poke(writeReady.B)
        dut.io.completion.ready.poke(completionReady.B)
        if (writeReady && dut.io.cuteWrite.valid.peek().litToBoolean) {
          val w = dut.io.cuteWrite.bits
          val n = if (row == 3 && chunk == 1) 1 else 8
          val offset = chunk % 4 * 8
          w.matrixReg.expect(2.U)
          w.bank.expect(((row + 5) % 8).U)
          w.entry.expect((((row + 5) / 8) * 2 + chunk / 4).U)
          w.byteMask.expect((((BigInt(1) << n) - 1) << offset).U)
          w.data.expect((pack(Seq.fill(n)(BigInt(bytes(row))), 8) << (offset * 8)).U)
          chunk += 1
          if (chunk == widths(row)) { row += 1; chunk = 0 }
        }
        if (completionReady && dut.io.completion.valid.peek().litToBoolean) {
          val c = dut.io.completion.bits
          c.jobId.expect(completed.U)
          c.pc.expect((0x80001000L + completed * 4).U)
          c.fflags.expect(flags(completed).U)
          c.exception.expect(false.B)
          completed += 1
        }
        dut.clock.step()
        cycles += 1
      }
      completed shouldBe 4
      row shouldBe 4
      dut.io.busy.expect(false.B)
      // Flush both queued descriptors and an occupied arithmetic pipeline.
      dut.io.command.valid.poke(true.B)
      dut.clock.step(3)
      dut.io.command.valid.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.clock.step(5)
      dut.io.completion.valid.expect(false.B)
      dut.io.cuteWrite.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  it should "capture one scalar scale per command and ignore inactive scale lanes under CUTE backpressure" in {
    test(new EvuSpecialSequencer(EvuParams(enable = true))).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      def clear(x: Data): Unit = x match {
        case b: Bool => b.poke(false.B)
        case u: UInt => u.poke(0.U)
        case s: SInt => s.poke(0.S)
        case v: Vec[_] => v.foreach(clear)
        case b: Bundle => b.elements.values.foreach(clear)
      }
      clear(dut.io.command.bits)
      clear(dut.io.matrix.in.bits)
      dut.io.command.valid.poke(false.B)
      dut.io.completion.ready.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.invalidate.poke(false.B)
      dut.io.matrix.in.valid.poke(false.B)
      dut.io.matrix.out.ready.poke(true.B)
      dut.io.vrf.read.foreach(_.ready.poke(true.B))
      dut.io.vrf.readData.foreach(_.poke(0.U))
      dut.io.cuteWrite.ready.poke(false.B)
      for (scale <- Seq(1f, 3f)) {
        dut.io.command.bits.opcode.poke(EvuOpcode.FpQuantToCute)
        dut.io.command.bits.aux.poke(0x10.U)
        dut.io.command.bits.sew.poke(2.U)
        dut.io.command.bits.vd.poke(4.U)
        dut.io.command.bits.vs2.poke(3.U)
        dut.io.command.bits.vl.poke(64.U)
        dut.io.command.bits.base.poke(1.U)
        dut.io.command.valid.poke(true.B)
        dut.io.command.ready.expect(true.B)
        dut.clock.step()
        dut.io.command.valid.poke(false.B)
        var reads = 0
        var writes = 0
        var cycles = 0
        while (!dut.io.completion.valid.peek().litToBoolean && cycles < 150) {
          dut.io.vrf.readData(0).poke(pack(Seq.fill(8)(raw(0.5f)), 32).U)
          dut.io.vrf.readData(1).poke(pack(raw(scale) +: Seq.fill(7)(BigInt("7f800001", 16)), 32).U)
          if (dut.io.vrf.read(1).valid.peek().litToBoolean) {
            dut.io.vrf.read(1).bits.chunk.expect(0.U)
            reads += 1
          }
          val ready = cycles % 3 == 2
          dut.io.cuteWrite.ready.poke(ready.B)
          if (ready && dut.io.cuteWrite.valid.peek().litToBoolean) {
            val offset = writes % 4 * 8
            val byte = if (scale == 1f) 1 else 2
            dut.io.cuteWrite.bits.data.expect((pack(Seq.fill(8)(BigInt(byte)), 8) << (offset * 8)).U)
            writes += 1
          }
          dut.clock.step()
          cycles += 1
        }
        dut.io.completion.valid.expect(true.B)
        dut.io.completion.bits.fflags.expect(0.U)
        dut.io.completion.bits.exception.expect(false.B)
        reads shouldBe 1
        writes shouldBe 8
        dut.io.completion.ready.poke(true.B)
        dut.clock.step()
        dut.io.completion.ready.poke(false.B)
      }
    }
  }

  it should "round multiply/add separately, clamp and report only active IEEE flags" in {
    test(new EvuFpQuant(EvuParams(enable = true))).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val input = Seq(0f, 0.49f, 0.5f, 1.5f, 63.25f, 127f, 128f, -1f)
      dut.io.flush.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      def check(values: Seq[BigInt], mask: Int, expected: Seq[BigInt], flags: Int): Unit = {
        dut.io.in.bits.input.poke(pack(values, 32).U)
        dut.io.in.bits.scale.poke(pack(Seq.fill(8)(raw(1f)), 32).U)
        dut.io.in.bits.validLanes.poke(mask.U)
        dut.io.in.bits.chunk.poke(3.U)
        dut.io.in.valid.poke(true.B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.clock.step(2)
        for (_ <- 0 until 4) {
          dut.io.out.valid.expect(true.B)
          dut.io.out.bits.data.expect(pack(expected, 8).U)
          dut.io.out.bits.fflags.expect(flags.U)
          dut.io.out.bits.chunk.expect(3.U)
          dut.clock.step()
        }
        dut.io.out.ready.poke(true.B)
        dut.clock.step()
        dut.io.out.ready.poke(false.B)
      }
      check(input.map(raw), 255, Seq(0, 0, 1, 2, 63, 127, 127, 0).map(BigInt(_)), 1)
      val snans = Seq(raw(0.5f)) ++ Seq.fill(7)(BigInt("7f800001", 16))
      check(snans, 1, Seq(BigInt(1)) ++ Seq.fill(7)(BigInt(0)), 0)
      check(snans, 255, Seq(BigInt(1)) ++ Seq.fill(7)(BigInt(127)), 16)
    }
  }

  it should "accept consecutive groups and flush every occupied stage under backpressure" in {
    test(new EvuFpQuant(EvuParams(enable = true))).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.in.bits.scale.poke(pack(Seq.fill(8)(raw(1f)), 32).U)
      dut.io.in.bits.validLanes.poke(255.U)
      for (cycle <- 0 until 11) {
        dut.io.in.valid.poke((cycle < 8).B)
        dut.io.in.bits.input.poke(pack(Seq.fill(8)(raw(cycle.toFloat)), 32).U)
        dut.io.in.bits.chunk.poke((cycle % 8).U)
        if (cycle < 8) dut.io.in.ready.expect(true.B)
        if (cycle >= 3) {
          dut.io.out.valid.expect(true.B)
          dut.io.out.bits.chunk.expect((cycle - 3).U)
          dut.io.out.bits.data.expect(pack(Seq.fill(8)(BigInt(cycle - 3)), 8).U)
        }
        dut.clock.step()
      }
      dut.io.out.valid.expect(false.B)
      dut.io.out.ready.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.clock.step(3)
      dut.io.in.ready.expect(false.B)
      dut.io.flush.poke(true.B)
      dut.io.out.valid.expect(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step(4)
      dut.io.out.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  it should "map packed bytes across banks and entries and hold under CUTE backpressure" in {
    test(new EvuSpecialSequencer(EvuParams(enable = true))).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      def clear(x: Data): Unit = x match {
        case b: Bool => b.poke(false.B)
        case u: UInt => u.poke(0.U)
        case s: SInt => s.poke(0.S)
        case v: Vec[_] => v.foreach(clear)
        case b: Bundle => b.elements.values.foreach(clear)
      }
      clear(dut.io.command.bits)
      clear(dut.io.matrix.in.bits)
      dut.io.command.valid.poke(false.B)
      dut.io.completion.ready.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.invalidate.poke(false.B)
      dut.io.matrix.in.valid.poke(false.B)
      dut.io.matrix.out.ready.poke(true.B)
      dut.io.vrf.read.foreach(_.ready.poke(true.B))
      dut.io.vrf.readData.foreach(_.poke(0.U))
      dut.io.cuteWrite.ready.poke(false.B)
      for (vl <- Seq(0, 1, 7, 8, 9, 31, 32, 33, 63, 64)) {
        val c = dut.io.command.bits
        c.opcode.poke(EvuOpcode.FpQuantToCute)
        c.sew.poke(2.U)
        c.vd.poke(4.U)
        c.vs2.poke(3.U)
        c.vl.poke(vl.U)
        c.base.poke(((127 << 2) | 2).U)
        c.config.valid.poke(true.B)
        c.config.configured.poke(true.B)
        c.config.vl.poke(vl.U)
        c.config.vtype.poke(16.U)
        c.jobId.poke(3.U)
        dut.io.command.valid.poke(true.B)
        dut.io.command.ready.expect(true.B)
        dut.clock.step()
        dut.io.command.valid.poke(false.B)
        var writes = 0
        var cycles = 0
        var held: Option[(BigInt, BigInt, BigInt)] = None
        while (!dut.io.completion.valid.peek().litToBoolean && cycles < 300) {
          for (i <- 0 until 3) {
            val addr = dut.io.vrf.read(i).bits.addr.peek().litValue
            val chunk = dut.io.vrf.read(i).bits.chunk.peek().litValue.toInt
            val data = if (addr == 3) (0 until 8).map(j => raw((chunk * 8 + j).toFloat + 0.25f))
              else Seq.fill(8)(raw(1f))
            dut.io.vrf.readData(i).poke(pack(data, 32).U)
          }
          val ready = cycles % 3 == 2
          dut.io.cuteWrite.ready.poke(ready.B)
          if (dut.io.cuteWrite.valid.peek().litToBoolean) {
            val w = dut.io.cuteWrite.bits
            val state = (w.entry.peek().litValue, w.data.peek().litValue, w.byteMask.peek().litValue)
            held.foreach(old => state shouldBe old)
            if (ready) {
              val start = writes * 8
              val n = math.min(8, vl - start)
              w.matrixReg.expect(2.U)
              w.bank.expect(7.U)
              w.entry.expect((30 + start / 32).U)
              w.byteMask.expect((((BigInt(1) << n) - 1) << (start % 32)).U)
              w.data.expect((pack((0 until n).map(i => BigInt(start + i)), 8) << ((start % 32) * 8)).U)
              writes += 1
              held = None
            } else held = Some(state)
          }
          dut.clock.step()
          cycles += 1
        }
        withClue(s"vl=$vl cycles=$cycles: ") {
          dut.io.completion.valid.expect(true.B)
          writes shouldBe ((vl + 7) / 8)
          dut.io.completion.bits.exception.expect(false.B)
          dut.io.completion.bits.vxsat.expect(false.B)
          dut.io.completion.bits.fflags.expect((if (vl == 0) 0 else 1).U)
          dut.io.completion.bits.jobId.expect(3.U)
        }
        dut.io.completion.ready.poke(true.B)
        dut.clock.step()
        dut.io.completion.ready.poke(false.B)
      }
    }
  }
}
