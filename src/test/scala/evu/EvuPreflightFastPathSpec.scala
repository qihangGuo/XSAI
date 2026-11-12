package evu

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

class EvuPreflightFastPathHarness extends Module {
  private val ep = EvuParams(enable = true)
  private val checker = Module(new EvuAddressCheck(ep))
  private val pipeline = Module(new EvuPreflightPipeline)
  val io = IO(new Bundle {
    val range = Flipped(new EvuRangeIO(ep))
    val translation = chiselTypeOf(pipeline.io.translation)
    val permissionFault = Input(Bool())
    val busy = Output(Bool())
  })
  checker.io.range <> io.range
  pipeline.io.request <> checker.io.lookup.req
  checker.io.lookup.resp <> pipeline.io.response
  pipeline.io.cancel := io.range.cancel
  pipeline.io.permissionFault := io.permissionFault
  io.translation <> pipeline.io.translation
  io.busy := pipeline.io.busy
}

class EvuPreflightFastPathSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU preflight boundary forwarding"

  private val shapes = Seq(
    ("E0 load", BigInt("40000024", 16), 4, false, false),
    ("E0 store", BigInt("40000024", 16), 4, true, false),
    ("single full-line store", BigInt("40000040", 16), 64, true, true),
    ("eight-line store", BigInt("40000000", 16), 512, true, true),
    ("cross-page load", BigInt("40000ffc", 16), 128, false, false),
    ("cross-page RMW store", BigInt("40000ffc", 16), 128, true, false)
  )

  for ((name, base, length, store, full) <- shapes; stalled <- Seq(false, true)) {
    it should s"check the complete $name with no boundary bubbles and retain stalled results (stalled=$stalled)" in {
      test(new EvuPreflightFastPathHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        val firstLine = base & ~BigInt(63)
        val lines = ((base + length - 1 - firstLine) / 64).toInt + 1
        val checks = (0 until lines).flatMap { line =>
          val writes = if (!store) Seq(false) else if (full) Seq(true) else Seq(false, true)
          writes.map(write => (firstLine + line * 64, write))
        }
        val req = dut.io.range.req
        req.bits.base.poke(base.U)
        req.bits.length.poke(length.U)
        req.bits.read.poke((!store).B)
        req.bits.write.poke(store.B)
        req.bits.fullLineWrite.poke(full.B)
        req.bits.alignment.poke(2.U)
        req.bits.illegal.poke(false.B)
        dut.io.range.cancel.poke(false.B)
        dut.io.permissionFault.poke(false.B)
        var accepted = false
        var returned = false
        var issued = 0
        var held = 0
        var cycle = 0
        var answer = Option.empty[(BigInt, BigInt)]
        while (!returned && cycle < 100) {
          req.valid.poke((!accepted).B)
          val out = dut.io.translation.request
          out.ready.poke((!stalled || (cycle >= 2 && cycle % 5 != 1)).B)
          val in = dut.io.translation.response
          in.valid.poke(answer.nonEmpty.B)
          in.bits.tag.poke(answer.map(_._1).getOrElse(BigInt(0)).U)
          val address = answer.map(_._2).getOrElse(base)
          val pageIndex = (address >> 12) - (base >> 12)
          val ppn = BigInt("80000", 16) + pageIndex * 0x10000
          in.bits.answer.paddr.poke(((ppn << 12) | (address & 4095)).U)
          in.bits.answer.gpaddr.poke(address.U)
          in.bits.answer.nonLeaf.poke(false.B)
          in.bits.answer.fault.poke(EvuAddressFault.None)
          in.bits.miss.poke(false.B)
          in.bits.uncacheable.poke(false.B)
          dut.io.range.resp.ready.poke((!stalled || held >= 3).B)
          if (req.valid.peek().litToBoolean && req.ready.peek().litToBoolean) accepted = true
          val next = if (out.valid.peek().litToBoolean && out.ready.peek().litToBoolean) {
            issued should be < checks.size
            out.bits.check.vaddr.expect(checks(issued)._1.U)
            out.bits.check.write.expect(checks(issued)._2.B)
            val event = (out.bits.tag.peek().litValue, checks(issued)._1)
            issued += 1
            Some(event)
          } else None
          if (dut.io.range.resp.valid.peek().litToBoolean) {
            issued shouldBe checks.size
            if (!stalled) cycle shouldBe checks.size + 1
            val result = dut.io.range.resp.bits
            result.fault.expect(EvuAddressFault.None)
            result.vaddr.expect(base.U)
            result.write.expect(store.B)
            result.pageMap.enabled.expect(true.B)
            result.pageMap.base.expect(base.U)
            result.pageMap.length.expect(length.U)
            val pages = ((base + length - 1) / 4096 - base / 4096).toInt + 1
            for (page <- 0 until pages) {
              result.pageMap.ppn(page).expect((BigInt("80000", 16) + page * 0x10000).U)
            }
            held += 1
            returned = dut.io.range.resp.ready.peek().litToBoolean
          }
          dut.clock.step()
          answer = next
          cycle += 1
        }
        returned shouldBe true
        if (stalled) held shouldBe 4
        dut.io.range.req.valid.poke(false.B)
        dut.io.translation.response.valid.poke(false.B)
        dut.io.range.resp.valid.expect(false.B)
        dut.io.range.req.ready.expect(true.B)
        dut.io.busy.expect(false.B)
      }
    }
  }

  for (fault <- Seq("read PMP", "write PMP", "guest page", "uncacheable", "changed PPN")) {
    it should s"include the final permission result and drain an E0 store with $fault" in {
      test(new EvuPreflightFastPathHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
        dut.io.range.cancel.poke(false.B)
        dut.io.range.resp.ready.poke(false.B)
        dut.io.translation.request.ready.poke(true.B)
        dut.io.translation.response.valid.poke(false.B)
        dut.io.permissionFault.poke(false.B)
        val req = dut.io.range.req
        req.valid.poke(true.B)
        req.bits.base.poke("h40000024".U)
        req.bits.length.poke(4.U)
        req.bits.read.poke(false.B)
        req.bits.write.poke(true.B)
        req.bits.fullLineWrite.poke(false.B)
        req.bits.alignment.poke(2.U)
        req.bits.illegal.poke(false.B)
        val out = dut.io.translation.request
        out.valid.expect(true.B)
        out.bits.check.write.expect(false.B)
        val readTag = out.bits.tag.peek().litValue
        dut.clock.step()
        req.valid.poke(false.B)
        out.valid.expect(true.B)
        out.bits.check.write.expect(true.B)
        val writeTag = out.bits.tag.peek().litValue
        val in = dut.io.translation.response
        in.valid.poke(true.B)
        in.bits.tag.poke(readTag.U)
        in.bits.answer.paddr.poke("h80000000".U)
        in.bits.answer.gpaddr.poke("h90000024".U)
        in.bits.answer.nonLeaf.poke((fault == "guest page").B)
        in.bits.answer.fault.poke(if (fault == "guest page") EvuAddressFault.GuestPage else EvuAddressFault.None)
        in.bits.miss.poke(false.B)
        in.bits.uncacheable.poke((fault == "uncacheable").B)
        dut.clock.step()
        dut.io.permissionFault.poke((fault == "read PMP" || fault == "guest page").B)
        in.bits.tag.poke(writeTag.U)
        in.bits.answer.paddr.poke((if (fault == "changed PPN") BigInt("a0000000", 16) else BigInt("80000000", 16)).U)
        in.bits.answer.fault.poke(EvuAddressFault.None)
        in.bits.answer.nonLeaf.poke(false.B)
        in.bits.uncacheable.poke(false.B)
        dut.io.range.resp.valid.expect(false.B)
        dut.clock.step()
        in.valid.poke(false.B)
        dut.io.permissionFault.poke((fault == "write PMP").B)
        val expected = if (fault == "guest page") EvuAddressFault.GuestPage else EvuAddressFault.Access
        dut.io.range.resp.valid.expect(true.B)
        dut.io.range.resp.bits.fault.expect(expected)
        dut.io.range.resp.bits.pageMap.enabled.expect(false.B)
        dut.io.range.resp.bits.vaddr.expect("h40000024".U)
        dut.io.range.resp.bits.gpaddr.expect("h90000024".U)
        dut.io.range.resp.bits.nonLeaf.expect((fault == "guest page").B)
        dut.io.range.resp.bits.write.expect(true.B)
        dut.clock.step()
        dut.io.permissionFault.poke(false.B)
        dut.io.range.resp.valid.expect(true.B)
        dut.io.range.resp.bits.fault.expect(expected)
        dut.io.range.resp.ready.poke(true.B)
        dut.clock.step()
        dut.io.busy.expect(false.B)
        dut.io.range.req.ready.expect(true.B)
      }
    }
  }

  it should "cancel a single-line result in the permission forwarding cycle" in {
    test(new EvuPreflightFastPathHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.range.cancel.poke(false.B)
      dut.io.range.resp.ready.poke(true.B)
      dut.io.translation.request.ready.poke(true.B)
      dut.io.translation.response.valid.poke(false.B)
      dut.io.permissionFault.poke(false.B)
      val req = dut.io.range.req
      req.valid.poke(true.B)
      req.bits.base.poke("h40000024".U)
      req.bits.length.poke(4.U)
      req.bits.read.poke(true.B)
      req.bits.write.poke(false.B)
      req.bits.fullLineWrite.poke(false.B)
      req.bits.alignment.poke(2.U)
      req.bits.illegal.poke(false.B)
      val tag = dut.io.translation.request.bits.tag.peek().litValue
      dut.clock.step()
      req.valid.poke(false.B)
      val in = dut.io.translation.response
      in.valid.poke(true.B)
      in.bits.tag.poke(tag.U)
      in.bits.answer.paddr.poke("h80000000".U)
      in.bits.answer.gpaddr.poke(0.U)
      in.bits.answer.nonLeaf.poke(false.B)
      in.bits.answer.fault.poke(EvuAddressFault.None)
      in.bits.miss.poke(false.B)
      in.bits.uncacheable.poke(false.B)
      dut.clock.step()
      in.valid.poke(false.B)
      dut.io.range.resp.valid.expect(true.B)
      dut.io.range.cancel.poke(true.B)
      dut.io.range.resp.valid.expect(false.B)
      dut.io.translation.request.valid.expect(false.B)
      dut.clock.step()
      dut.io.range.cancel.poke(false.B)
      dut.clock.step(3)
      dut.io.range.resp.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }
}
