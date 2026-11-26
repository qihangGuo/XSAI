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

class EvuMapAdmissionHarness(p: EvuParams) extends Module {
  private val width = 6
  val io = IO(new Bundle {
    val offered = Input(UInt(3.W))
    val firstOwner = Input(UInt(8.W))
    val redirect = Input(Bool())
    val release = Input(UInt(32.W))
    val write = Flipped(Valid(new Bundle { val slot = UInt(5.W); val owner = UInt(8.W) }))
    val accepted = Output(UInt(3.W))
    val registered = Output(UInt(3.W))
    val occupied = Output(UInt(6.W))
    val read = Input(UInt(5.W))
    val readBase = Output(UInt(64.W))
  })
  private val pool = Module(new EvuPageMapPool(p, 160, width, 1, 1))
  private val atRob = RegNext(io.accepted, 0.U)
  private val owner = RegNext(io.firstOwner)
  private val (robReady, dispatchReady) = EvuPageMapPool.admission(pool.io.occupied, atRob, io.accepted, width)
  io.accepted := Mux(dispatchReady && !io.redirect, io.offered, 0.U)
  io.registered := Mux(robReady, atRob, 0.U)
  for (i <- 0 until width) {
    pool.io.reserve(i).valid := i.U < io.registered
    pool.io.reserve(i).bits := owner + i.U
  }
  io.occupied := pool.io.occupied
  pool.io.release := io.release
  pool.io.cancelOwners := 0.U
  pool.io.read(0) := io.read
  pool.io.readBase(0) := 0.U
  io.readBase := pool.io.data(0).ppn(0) << 12
  pool.io.write(0).valid := io.write.valid
  pool.io.write(0).bits.slot := io.write.bits.slot
  pool.io.write(0).bits.owner := io.write.bits.owner
  pool.io.write(0).bits.map := 0.U.asTypeOf(pool.io.write(0).bits.map)
  pool.io.write(0).bits.map.ppn(0) := io.write.bits.owner
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

  it should "honor every dispatched page-map credit across the non-replaying ROB enqueue stage" in {
    test(new EvuMapAdmissionHarness(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      clear(dut.io.write)
      dut.io.read.poke(0.U)
      var previous = 0
      var live = 0
      def tick(offered: Int, owner: Int, release: BigInt = 0, redirect: Boolean = false): Int = {
        dut.io.offered.poke(offered.U)
        dut.io.firstOwner.poke(owner.U)
        dut.io.release.poke(release.U)
        dut.io.redirect.poke(redirect.B)
        dut.io.occupied.expect(live.U)
        dut.io.registered.expect(previous.U)
        val accepted = dut.io.accepted.peek().litValue.toInt
        live += previous - release.bitCount
        assert(live <= 32)
        previous = accepted
        dut.clock.step()
        accepted
      }
      // At 26 occupied the old shared grant dispatched 137, then refused to register it.
      for (n <- 0 until 26) tick(1, n) shouldBe 1
      tick(0, 0)
      tick(1, 135) shouldBe 1
      tick(1, 137, release = 1) shouldBe 0
      tick(1, 137) shouldBe 0
      tick(1, 137) shouldBe 1
      tick(0, 0)
      tick(0, 0)
      for ((tag, slot) <- Seq(135 -> 26, 137 -> 0)) {
        dut.io.write.valid.poke(true.B)
        dut.io.write.bits.slot.poke(slot.U)
        dut.io.write.bits.owner.poke(tag.U)
        dut.io.read.poke(slot.U)
        tick(0, 0)
        dut.io.readBase.expect((tag << 12).U)
      }
      dut.io.write.valid.poke(false.B)
      tick(0, 0, (BigInt(1) << live) - 1)
      tick(0, 0)
      // Four full dispatch groups, a killed group and another full-width burst.
      for (i <- 0 until 4) tick(6, i * 6) shouldBe 6
      tick(6, 100, redirect = true) shouldBe 0
      tick(6, 110) shouldBe 6
      tick(6, 120)
      tick(0, 0)
      tick(0, 0)
      live shouldBe 30
    }
  }

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
        dut.io.readBase(0).poke((owner * 4096).U)
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
      clear(dut.io.fault)
      dut.io.lookup.poke(0.U)
      dut.io.flush.poke(false.B)
      for (tag <- Seq(30, 31, 0, 1)) {
        dut.io.allocate.valid.poke(true.B)
        dut.io.allocate.bits.tag.poke(tag.U)
        dut.io.allocate.bits.pageMap.enabled.poke((tag != 1).B)
        dut.io.allocate.bits.pageMap.base.poke((tag * 4096 + 0xfc0).U)
        dut.io.allocate.bits.pageMap.length.poke((tag + 1).U)
        dut.io.allocate.bits.pageMap.ppn.foreach(_.poke((tag + 100).U))
        dut.clock.step()
      }
      dut.io.allocate.valid.poke(false.B)
      for (tag <- Seq(30, 31, 0, 1)) {
        dut.io.lookup.poke(tag.U)
        dut.io.found.expect(true.B)
        dut.io.virtualBase.expect((if (tag == 1) 0 else tag * 4096).U)
        dut.io.pageMap.ppn.foreach(_.expect((tag + 100).U))
      }
      dut.io.fault.valid.poke(true.B)
      dut.io.fault.bits.vat.poke(31.U)
      dut.io.fault.bits.address.poke(0x1234.U)
      dut.io.fault.bits.cause.poke(EvuFault.Access)
      dut.clock.step()
      dut.io.fault.bits.address.poke(0xdead.U)
      dut.io.fault.bits.cause.poke(EvuFault.Bus)
      dut.clock.step()
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.poke(31.U)
      dut.io.allocate.valid.poke(true.B)
      dut.io.allocate.bits.tag.poke(2.U)
      dut.io.allocate.bits.pageMap.enabled.poke(true.B)
      dut.io.allocate.bits.pageMap.base.poke(0x9080.U)
      dut.io.lookup.poke(30.U)
      dut.io.found.expect(true.B)
      dut.io.virtualBase.expect((30 * 4096).U)
      dut.io.firstFault.valid.expect(true.B)
      dut.io.firstFault.bits.address.expect(0x1234.U)
      dut.io.firstFault.bits.cause.expect(EvuFault.Access)
      dut.clock.step()
      dut.io.fault.valid.poke(false.B)
      dut.io.allocate.valid.poke(false.B)
      dut.io.release.valid.poke(false.B)
      dut.io.release.bits.poke(2.U)
      dut.io.firstFault.valid.expect(false.B)
      dut.io.lookup.poke(31.U)
      dut.io.found.expect(false.B)
      dut.io.lookup.poke(2.U)
      dut.io.found.expect(true.B)
      dut.io.virtualBase.expect(0x9000.U)
      dut.io.lookup.poke(30.U)
      dut.io.found.expect(true.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.fault.valid.poke(true.B)
      dut.io.fault.bits.vat.poke(2.U)
      dut.clock.step()
      dut.io.firstFault.valid.expect(false.B)
      for (tag <- Seq(30, 0, 1, 2)) {
        dut.io.lookup.poke(tag.U)
        dut.io.found.expect(false.B)
      }
    }
  }
}
