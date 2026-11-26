package evu

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuCommandPackingHarness(p: EvuParams) extends Module {
  val io = IO(new Bundle {
    val full = Input(new EvuCoreCommand(p))
    val restored = Output(new EvuCoreCommand(p))
    val identical = Output(Bool())
    val amuIdentical = Output(Bool())
  })
  io.restored := EvuCoreCommandData.expand(EvuCoreCommandData.compact(io.full, p), io.full.pageMap, p)
  val originalPacking = Cat(io.full.pc, io.full.config.asUInt, io.full.instr, io.full.base,
    io.full.vl, io.full.length, io.full.aux, io.full.pageMap.asUInt)
  io.identical := io.full.asUInt === io.restored.asUInt && io.full.asUInt === originalPacking
  val stored = EvuAmuCommandData.pack(io.full, p)
  require(stored.getWidth == (new EvuCoreCommandData(p)).getWidth - 32 - io.full.vl.getWidth)
  io.amuIdentical := stored.expand(io.full.pageMap).asUInt === originalPacking
}

class EvuAreaStorageSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val ep = EvuParams(enable = true)
  private def clear(data: Data): Unit = data match {
    case b: Bool => b.poke(false.B)
    case u: UInt => u.poke(0.U)
    case s: SInt => s.poke(0.S)
    case v: Vec[_] => v.foreach(clear)
    case b: Bundle => b.elements.values.foreach(clear)
  }

  behavior of "Compacted EVU storage"

  it should "round trip every command field without changing the full transport format" in {
    test(new EvuCommandPackingHarness(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      clear(dut.io.full)
      dut.io.full.pc.poke(BigInt("8000123456789abc", 16).U)
      dut.io.full.instr.poke(0x1234567b.U)
      dut.io.full.base.poke(0x87654321L.U)
      dut.io.full.vl.poke(127.U)
      dut.io.full.length.poke(508.U)
      dut.io.full.aux.poke(0xa5.U)
      dut.io.full.config.valid.poke(true.B)
      dut.io.full.pageMap.enabled.poke(true.B)
      dut.io.full.pageMap.base.poke(0x87654000L.U)
      dut.io.full.pageMap.length.poke(508.U)
      dut.io.full.pageMap.ppn.zipWithIndex.foreach { case (v, i) => v.poke((0x100 + i).U) }
      dut.io.identical.expect(true.B)
    }
  }

  it should "restore every CPU command bit from the narrow AMU encoding" in {
    test(new EvuCommandPackingHarness(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val c = dut.io.full
      val random = new scala.util.Random(42)
      clear(c)
      c.length.poke(ep.vlenBytes.U)
      for (op <- Seq(0x10, 0x11, 0x1a, 0x32, 0x45, 0x46); sew <- 0 until 8;
           vl <- Seq(0, 17, ep.vlenBits)) {
        val instr = (BigInt(op) << 25) | (BigInt(10, random) << 15) |
          (BigInt(sew) << 12) | (BigInt(5, random) << 7) | 0x0b
        c.instr.poke(instr.U)
        c.pc.poke(BigInt(64, random).U)
        c.base.poke(BigInt(64, random).U)
        c.aux.poke(BigInt(8, random).U)
        c.config.configured.poke(random.nextBoolean().B)
        c.config.valid.poke(random.nextBoolean().B)
        c.config.vill.poke(random.nextBoolean().B)
        c.config.vtype.poke(BigInt(8, random).U)
        c.config.vl.poke(vl.U)
        val rawVl = ep.vlenBytes >> (if (sew <= 3) sew else 0)
        c.vl.poke((if (op == 0x45 || op == 0x46) vl else rawVl).U)
        c.pageMap.enabled.poke((op == 0x10 || op == 0x11).B)
        c.pageMap.base.poke(BigInt(64, random).U)
        c.pageMap.length.poke(BigInt(32, random).U)
        c.pageMap.ppn.foreach(ppn => ppn.poke(BigInt(ppn.getWidth, random).U))
        dut.io.amuIdentical.expect(true.B)
      }
    }
  }

  it should "reserve CPU translations in order and retain them across arbitrary writeback and release order" in {
    test(new EvuPageMapPool(ep, 160, 4, 1, 1)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      clear(dut.io.reserve)
      clear(dut.io.write)
      dut.io.read(0).poke(0.U)
      dut.io.release.poke(0.U)
      dut.io.cancelOwners.poke(0.U)
      val slots = scala.collection.mutable.Map.empty[Int, Int]
      for (batch <- 0 until 8) {
        for (port <- 0 until 4) {
          val owner = batch * 4 + port
          dut.io.reserve(port).valid.poke(true.B)
          dut.io.reserve(port).bits.poke(owner.U)
          slots(owner) = dut.io.slots(port).peek().litValue.toInt
        }
        dut.clock.step()
      }
      clear(dut.io.reserve)
      dut.io.occupied.expect(32.U)
      slots.values.toSet.size shouldBe 32
      for (owner <- (0 until 32).reverse) {
        val write = dut.io.write(0)
        write.valid.poke(true.B)
        write.bits.slot.poke(slots(owner).U)
        write.bits.owner.poke(owner.U)
        write.bits.map.enabled.poke(true.B)
        write.bits.map.base.poke((owner * 4096).U)
        write.bits.map.length.poke((owner + 1).U)
        write.bits.map.ppn.foreach(_.poke((owner + 0x100).U))
        dut.clock.step()
      }
      dut.io.write(0).valid.poke(false.B)
      for (owner <- 0 until 32) {
        dut.io.read(0).poke(slots(owner).U)
        dut.io.data(0).base.expect((owner * 4096).U)
        dut.io.data(0).length.expect((owner + 1).U)
        dut.io.data(0).ppn.foreach(_.expect((owner + 0x100).U))
      }
      val cancel = (0 until 32 by 2).foldLeft(BigInt(0))((m, i) => m.setBit(i))
      val release = (1 until 32 by 2).foldLeft(BigInt(0))((m, i) => m.setBit(slots(i)))
      dut.io.cancelOwners.poke(cancel.U)
      dut.io.release.poke(release.U)
      dut.clock.step()
      dut.io.occupied.expect(0.U)
      dut.io.cancelOwners.poke(0.U)
      dut.io.release.poke(0.U)
      dut.io.reserve(0).valid.poke(true.B)
      dut.io.reserve(0).bits.poke(100.U)
      dut.io.slots(0).expect(0.U)
      dut.clock.step()
      clear(dut.io.reserve)
      dut.io.occupied.expect(1.U)
      dut.io.cancelOwners.poke((BigInt(1) << 100).U)
      dut.clock.step()
      dut.io.occupied.expect(0.U)
    }
  }

  it should "reuse a full memory translation slot without losing other live mappings" in {
    test(new EvuMemoryContextTable(ep, 5)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      clear(dut.io.allocate)
      clear(dut.io.release)
      dut.io.lookup.poke(0.U)
      dut.io.flush.poke(false.B)
      for (tag <- Seq(30, 31, 0, 1)) {
        dut.io.allocate.valid.poke(true.B)
        dut.io.allocate.bits.tag.poke(tag.U)
        dut.io.allocate.bits.virtualBase.poke((tag * 4096).U)
        dut.io.allocate.bits.pageMap.enabled.poke(true.B)
        dut.io.allocate.bits.pageMap.base.poke((tag * 4096).U)
        dut.io.allocate.bits.pageMap.length.poke((tag + 1).U)
        dut.io.allocate.bits.pageMap.ppn.foreach(_.poke((tag + 100).U))
        dut.clock.step()
      }
      dut.io.allocate.valid.poke(false.B)
      for (tag <- Seq(30, 31, 0, 1)) {
        dut.io.lookup.poke(tag.U)
        dut.io.found.expect(true.B)
        dut.io.virtualBase.expect((tag * 4096).U)
        dut.io.pageMap.ppn.foreach(_.expect((tag + 100).U))
      }
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.poke(31.U)
      dut.io.allocate.valid.poke(true.B)
      dut.io.allocate.bits.tag.poke(2.U)
      dut.io.allocate.bits.virtualBase.poke(0x9000.U)
      dut.io.lookup.poke(31.U)
      dut.io.found.expect(true.B)
      dut.clock.step()
      dut.io.allocate.valid.poke(false.B)
      dut.io.release.valid.poke(false.B)
      dut.io.found.expect(false.B)
      dut.io.lookup.poke(2.U)
      dut.io.found.expect(true.B)
      dut.io.virtualBase.expect(0x9000.U)
      dut.io.lookup.poke(30.U)
      dut.io.found.expect(true.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      for (tag <- Seq(30, 0, 1, 2)) {
        dut.io.lookup.poke(tag.U)
        dut.io.found.expect(false.B)
      }
    }
  }
}
