package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuAddressCheckSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU address preflight"

  it should "retain all seven opcode bits when encoding Saturn memory instructions" in {
    xiangshan.evu.common.VectorConsts.opcLoad.getWidth shouldBe 7
    xiangshan.evu.common.VectorConsts.opcStore.getWidth shouldBe 7
  }

  it should "check both permissions on every line and retain noncontiguous page mappings" in {
    val ep = EvuParams(enable = true)
    test(new EvuAddressCheck(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.range.cancel.poke(false.B)
      dut.io.allowPhysicalLaunch.poke(false.B)
      dut.io.range.req.valid.poke(false.B)
      dut.io.range.resp.ready.poke(false.B)
      dut.io.lookup.req.ready.poke(false.B)
      dut.io.lookup.resp.valid.poke(false.B)
      dut.io.lookup.resp.bits.paddr.poke(0.U)
      dut.io.lookup.resp.bits.gpaddr.poke(0.U)
      dut.io.lookup.resp.bits.nonLeaf.poke(false.B)
      dut.io.lookup.resp.bits.fault.poke(EvuAddressFault.None)
      val base = BigInt("40000ffc", 16)
      dut.io.range.req.bits.base.poke(base.U)
      dut.io.range.req.bits.length.poke(512.U)
      dut.io.range.req.bits.read.poke(true.B)
      dut.io.range.req.bits.write.poke(true.B)
      dut.io.range.req.bits.alignment.poke(2.U)
      dut.io.range.req.bits.unbounded.poke(false.B)
      dut.io.range.req.bits.illegal.poke(false.B)
      dut.io.range.req.valid.poke(true.B)
      dut.io.range.req.ready.expect(true.B)
      dut.clock.step()
      dut.io.range.req.valid.poke(false.B)
      for (line <- 0 until 9; write <- Seq(false, true)) {
        val address = (base & ~BigInt(63)) + line * 64
        dut.io.lookup.req.valid.expect(true.B)
        dut.io.lookup.req.bits.vaddr.expect(address.U)
        dut.io.lookup.req.bits.write.expect(write.B)
        dut.clock.step(2)
        dut.io.lookup.req.bits.vaddr.expect(address.U)
        dut.io.lookup.req.ready.poke(true.B)
        dut.clock.step()
        dut.io.lookup.req.ready.poke(false.B)
        dut.io.range.resp.valid.expect(false.B)
        val physicalPage = if (line == 0) BigInt("80000", 16) else BigInt("90000", 16)
        dut.io.lookup.resp.bits.paddr.poke(((physicalPage << 12) | (address & 4095)).U)
        dut.io.lookup.resp.valid.poke(true.B)
        dut.clock.step()
        dut.io.lookup.resp.valid.poke(false.B)
      }
      dut.io.range.resp.valid.expect(true.B)
      dut.io.range.resp.bits.fault.expect(EvuAddressFault.None)
      dut.io.range.resp.bits.pageMap.enabled.expect(true.B)
      dut.io.range.resp.bits.pageMap.ppn(0).expect("h80000".U)
      dut.io.range.resp.bits.pageMap.ppn(1).expect("h90000".U)
      dut.clock.step(3)
      dut.io.range.resp.valid.expect(true.B)
      dut.io.range.resp.ready.poke(true.B)
      dut.clock.step()
      dut.io.range.req.ready.expect(true.B)
    }
  }

  it should "stop at a later-page fault, cancel an outstanding lookup, and allow a clean retry" in {
    val ep = EvuParams(enable = true)
    test(new EvuAddressCheck(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.range.cancel.poke(false.B)
      dut.io.allowPhysicalLaunch.poke(false.B)
      dut.io.range.resp.ready.poke(false.B)
      dut.io.lookup.req.ready.poke(true.B)
      dut.io.lookup.resp.valid.poke(false.B)
      dut.io.lookup.resp.bits.paddr.poke(0.U)
      dut.io.lookup.resp.bits.gpaddr.poke("h12345000".U)
      dut.io.lookup.resp.bits.nonLeaf.poke(true.B)
      dut.io.lookup.resp.bits.fault.poke(EvuAddressFault.None)
      def start(): Unit = {
        dut.io.range.req.bits.base.poke("h40000fc0".U)
        dut.io.range.req.bits.length.poke(128.U)
        dut.io.range.req.bits.read.poke(false.B)
        dut.io.range.req.bits.write.poke(true.B)
        dut.io.range.req.bits.alignment.poke(2.U)
        dut.io.range.req.bits.unbounded.poke(false.B)
        dut.io.range.req.bits.illegal.poke(false.B)
        dut.io.range.req.valid.poke(true.B)
        dut.io.range.req.ready.expect(true.B)
        dut.clock.step()
        dut.io.range.req.valid.poke(false.B)
      }
      def answer(address: BigInt, write: Boolean, fault: UInt): Unit = {
        dut.io.lookup.req.valid.expect(true.B)
        dut.io.lookup.req.bits.write.expect(write.B)
        dut.clock.step()
        dut.io.lookup.resp.bits.paddr.poke(address.U)
        dut.io.lookup.resp.bits.fault.poke(fault)
        dut.io.lookup.resp.valid.poke(true.B)
        dut.clock.step()
        dut.io.lookup.resp.valid.poke(false.B)
      }
      start()
      answer(BigInt("80000fc0", 16), false, EvuAddressFault.None)
      answer(BigInt("80000fc0", 16), true, EvuAddressFault.None)
      answer(0, false, EvuAddressFault.Page)
      dut.io.range.resp.valid.expect(true.B)
      dut.io.range.resp.bits.fault.expect(EvuAddressFault.Page)
      dut.io.range.resp.bits.vaddr.expect("h40001000".U)
      dut.io.range.resp.bits.write.expect(true.B)
      dut.io.range.resp.bits.pageMap.enabled.expect(false.B)
      dut.io.lookup.req.valid.expect(false.B)
      dut.io.range.resp.ready.poke(true.B)
      dut.clock.step()
      dut.io.range.resp.ready.poke(false.B)
      start()
      dut.clock.step() // Cancel while the TLB answer is outstanding.
      dut.io.range.cancel.poke(true.B)
      dut.clock.step()
      dut.io.range.cancel.poke(false.B)
      start()
      answer(BigInt("a0000fc0", 16), false, EvuAddressFault.None)
      answer(BigInt("a0000fc0", 16), true, EvuAddressFault.None)
      answer(BigInt("b0000000", 16), false, EvuAddressFault.None)
      answer(BigInt("b0000000", 16), true, EvuAddressFault.None)
      dut.io.range.resp.valid.expect(true.B)
      dut.io.range.resp.bits.fault.expect(EvuAddressFault.None)
      dut.io.range.resp.bits.pageMap.ppn(0).expect("ha0000".U)
      dut.io.range.resp.bits.pageMap.ppn(1).expect("hb0000".U)
    }
  }

  it should "reject malformed ranges and unbounded virtual output without issuing translations" in {
    val ep = EvuParams(enable = true)
    test(new EvuAddressCheck(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.range.cancel.poke(false.B)
      dut.io.allowPhysicalLaunch.poke(false.B)
      dut.io.range.resp.ready.poke(true.B)
      dut.io.lookup.req.ready.poke(true.B)
      dut.io.lookup.resp.valid.poke(false.B)
      dut.io.range.req.bits.read.poke(true.B)
      dut.io.range.req.bits.write.poke(false.B)
      dut.io.range.req.bits.alignment.poke(2.U)
      dut.io.range.req.bits.illegal.poke(false.B)
      val cases = Seq(
        (BigInt(3), 64, false, EvuAddressFault.Misaligned),
        ((BigInt(1) << 64) - 64, 128, false, EvuAddressFault.Access),
        (BigInt(0), 513, false, EvuAddressFault.Access),
        (BigInt(0), 512, true, EvuAddressFault.Illegal)
      )
      for ((base, length, unbounded, fault) <- cases) {
        dut.io.range.req.bits.base.poke(base.U)
        dut.io.range.req.bits.length.poke(length.U)
        dut.io.range.req.bits.unbounded.poke(unbounded.B)
        dut.io.range.req.valid.poke(true.B)
        dut.clock.step()
        dut.io.range.req.valid.poke(false.B)
        dut.io.lookup.req.valid.expect(false.B)
        dut.io.range.resp.valid.expect(true.B)
        dut.io.range.resp.bits.fault.expect(fault)
        dut.clock.step()
      }
      dut.io.range.req.bits.base.poke(0.U)
      dut.io.range.req.bits.length.poke(0.U)
      dut.io.range.req.bits.unbounded.poke(false.B)
      dut.io.range.req.bits.illegal.poke(true.B)
      dut.io.range.req.valid.poke(true.B)
      dut.clock.step()
      dut.io.range.req.valid.poke(false.B)
      dut.io.range.resp.bits.fault.expect(EvuAddressFault.Illegal)
      dut.io.lookup.req.valid.expect(false.B)
      dut.clock.step()
    }
  }

  it should "translate separate pages and reject requests outside the checked range" in {
    val ep = EvuParams(enable = true)
    test(new EvuPageTranslate(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.pageMap.enabled.poke(true.B)
      dut.io.pageMap.base.poke("hffffffffc0000ffc".U)
      dut.io.pageMap.length.poke(512.U)
      dut.io.pageMap.ppn(0).poke("h80000".U)
      dut.io.pageMap.ppn(1).poke("h90000".U)
      dut.io.vaddr.poke("hffffffffc0000fc0".U)
      dut.io.legal.expect(true.B)
      dut.io.paddr.expect("h80000fc0".U)
      dut.io.vaddr.poke("hffffffffc0001000".U)
      dut.io.legal.expect(true.B)
      dut.io.paddr.expect("h90000000".U)
      dut.io.vaddr.poke("hffffffffc0001200".U)
      dut.io.legal.expect(false.B)
      dut.io.vaddr.poke("hffffffffc0000f80".U)
      dut.io.legal.expect(false.B)
      dut.io.pageMap.enabled.poke(false.B)
      dut.io.vaddr.poke("h80000f80".U)
      dut.io.paddr.expect("h80000f80".U)
      dut.io.legal.expect(true.B)
    }
  }
}
