package xiangshan.evucheck

import chisel3._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.backend.decode.{EvuDecoder, XSDecode}
import xiangshan.backend.fu.NewCSR.EvuCSR
import xiangshan.backend.rob.{RobEnqueueBarrier, SetvliBarrierEvents}
import xiangshan.SrcType
import xiangshan.evu.{EvuDispatchAdapter, EvuIsa, EvuParams}

class SetvliPublicationProbe extends Module {
  private val ep = EvuParams()
  val io = IO(new Bundle {
    val enqueue = Input(Bool())
    val retire = Input(Bool())
    val tag = Input(UInt(4.W))
    val retiredTag = Input(UInt(4.W))
    val f7 = Input(UInt(7.W))
    val vl = Input(UInt(8.W))
    val illegal = Input(Bool())
    val cancel = Input(Bool())
    val busy = Input(Bool())
    val empty = Input(Bool())
    val issue = Input(Bool())
    val pop = Input(Bool())
    val admitted = Output(Bool())
    val blocked = Output(Bool())
    val published = Output(Bool())
    val headVl = Output(UInt(8.W))
    val headValid = Output(Bool())
  })
  val bank = Module(new EvuCSR(ep, 4))
  val barrier = Module(new RobEnqueueBarrier(4))
  val adapter = Module(new EvuDispatchAdapter(ep))
  val retired = WireInit(0.U.asTypeOf(bank.io.accepted))
  retired.valid := io.retire
  retired.bits.robTag := io.retiredTag
  val cmd = retired.bits.command
  cmd.instr := chisel3.util.Cat(io.f7, 0.U(18.W), EvuIsa.custom0)
  cmd.base := io.vl
  cmd.config.valid := true.B
  cmd.config.configured := true.B
  cmd.config.vtype := 0x10.U
  cmd.config.vill := io.illegal
  bank.io.accepted := RegNext(RegNext(retired, 0.U.asTypeOf(retired)), 0.U.asTypeOf(retired))
  bank.io.write := 0.U.asTypeOf(bank.io.write)
  bank.io.completed := 0.U.asTypeOf(bank.io.completed)
  bank.io.drain := 0.U.asTypeOf(bank.io.drain)
  bank.io.busy := io.busy
  barrier.io.enqueue.valid := io.enqueue
  barrier.io.enqueue.bits := false.B
  barrier.io.setvliOwner.valid := io.f7 === EvuIsa.setvli
  barrier.io.setvliOwner.bits := io.tag
  barrier.io.configPublished := bank.io.configPublished
  barrier.io.evuBusy := io.busy
  barrier.io.robEmpty := io.empty
  barrier.io.cancel := io.cancel
  io.published := bank.io.configPublished.valid
  io.blocked := barrier.io.blocked
  adapter.io.flush := false.B
  adapter.io.in.valid := io.issue && !barrier.io.blocked
  adapter.io.in.bits := 0.U.asTypeOf(adapter.io.in.bits)
  adapter.io.in.bits.instr := chisel3.util.Cat(EvuIsa.fmul, 0.U(18.W), EvuIsa.custom0)
  adapter.io.in.bits.config := bank.io.config
  adapter.io.out.ready := io.pop
  io.admitted := adapter.io.in.fire
  io.headValid := adapter.io.out.valid
  io.headVl := adapter.io.out.bits.config.vl
}

class SetvliBarrierProbe extends Module {
  val io = IO(new Bundle {
    val enqueue = Input(Bool())
    val setvli = Input(Bool())
    val empty = Input(Bool())
    val busy = Input(Bool())
    val demand = Input(Bool())
    val phase = Output(UInt(3.W))
  })
  val b = Module(new RobEnqueueBarrier(4))
  b.io.enqueue.valid := io.enqueue
  b.io.enqueue.bits := false.B
  b.io.setvliOwner.valid := io.setvli
  b.io.setvliOwner.bits := 0.U
  b.io.configPublished := 0.U.asTypeOf(b.io.configPublished)
  b.io.cancel := false.B
  b.io.robEmpty := io.empty
  b.io.evuBusy := io.busy
  val e = SetvliBarrierEvents(io.enqueue, io.setvli, b.io.blocked, io.demand, io.empty, io.busy)
  io.phase := chisel3.util.Cat(e.release, e.drainOnly, e.nonempty)
}

class EvuRobOrderingSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "ROB backward barriers with asynchronous EVU memory"

  it should "admit younger commands on matching publication and preserve queued 64/16/64 snapshots" in {
    test(new SetvliPublicationProbe).withAnnotations(Seq(TreadleBackendAnnotation)) { d =>
      val i = d.io
      i.busy.poke(true.B); i.empty.poke(true.B); i.cancel.poke(false.B)
      i.issue.poke(false.B); i.pop.poke(false.B); i.illegal.poke(false.B)
      def enqueue(tag: Int, f7: Int = 0x45): Unit = {
        i.f7.poke(f7.U); i.tag.poke(tag.U); i.enqueue.poke(true.B)
        d.clock.step(); i.enqueue.poke(false.B); i.blocked.expect(true.B)
      }
      def retire(tag: Int, vl: Int): Unit = {
        i.retiredTag.poke(tag.U); i.vl.poke(vl.U); i.retire.poke(true.B)
        d.clock.step(); i.retire.poke(false.B)
        i.published.expect(false.B); i.blocked.expect(true.B)
        d.clock.step(); i.published.expect(false.B); i.blocked.expect(true.B)
        d.clock.step()
      }
      i.retire.poke(false.B)
      for (((tag, vl), n) <- Seq((1, 64), (2, 16), (3, 64)).zipWithIndex) {
        enqueue(tag); i.issue.poke(true.B); i.admitted.expect(false.B)
        retire(tag, vl); i.published.expect(true.B); i.admitted.expect(false.B)
        d.clock.step(); i.admitted.expect(true.B)
        d.clock.step(); i.issue.poke(false.B)
        if (n > 0) {
          i.headVl.expect((if (n == 1) 64 else 16).U)
          i.pop.poke(true.B); d.clock.step(); i.pop.poke(false.B)
        }
        i.headValid.expect(true.B); i.headVl.expect(vl.U)
      }
      // Stale publication for the same slot but a different wrap bit.
      enqueue(11); retire(3, 64); i.published.expect(true.B)
      d.clock.step(); i.blocked.expect(true.B)
      // Even a matching tag cannot release an owner cancelled by redirect.
      i.cancel.poke(true.B); d.clock.step(); i.cancel.poke(false.B)
      retire(11, 16); d.clock.step(); i.blocked.expect(true.B)
      def drain(): Unit = {
        i.busy.poke(false.B); d.clock.step(); i.blocked.expect(false.B); i.busy.poke(true.B)
      }
      drain()
      for ((op, vill) <- Seq((0x45, true), (0x46, false), (0x1a, false))) {
        enqueue(4, op); i.illegal.poke(vill.B); retire(4, 16)
        i.published.expect(false.B); d.clock.step(); i.blocked.expect(true.B); drain()
      }
    }
  }

  it should "attribute only demanded post-SETVLI stalls to the matching drain phase" in {
    test(new SetvliBarrierProbe).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      val io = dut.io
      for (setvli <- Seq(true, false)) {
        io.enqueue.poke(true.B)
        io.setvli.poke(setvli.B)
        io.empty.poke(false.B)
        io.busy.poke(true.B)
        io.demand.poke(true.B)
        dut.clock.step()
        io.enqueue.poke(false.B)
        io.setvli.poke((!setvli).B) // Decode input is no longer the barrier's owner.
        io.phase.expect((if (setvli) 1 else 0).U)
        io.empty.poke(true.B)
        io.demand.poke(false.B)
        io.phase.expect(0.U)
        dut.clock.step(2)
        io.demand.poke(true.B)
        io.phase.expect((if (setvli) 2 else 0).U)
        io.busy.poke(false.B)
        io.phase.expect((if (setvli) 4 else 0).U)
        dut.clock.step()
        io.phase.expect(0.U)
      }
    }
  }

  it should "retain non-speculative decode and backward barriers for memory and control" in {
    val entries = EvuDecoder.decodeArray.map { case (pattern, decode) =>
      (pattern.value >> 25).toInt -> decode.asInstanceOf[XSDecode]
    }.toMap
    for (f7 <- Seq(0x10, 0x11, 0x52, 0x53, 0x1a, 0x45, 0x46)) {
      entries(f7).noSpec shouldBe true
      entries(f7).blockBack shouldBe true
    }
    for (retired <- Seq(0x18, 0x19, 0x50, 0x5e)) {
      entries.contains(retired) shouldBe false
    }
    for (slide <- Seq(0x3e, 0x3f)) {
      entries(slide).src1.value shouldBe SrcType.reg.litValue
      entries(slide).src1.mask shouldBe ((BigInt(1) << SrcType.reg.getWidth) - 1)
      entries(slide).xWen shouldBe false
      entries(slide).noSpec shouldBe false
      entries(slide).blockBack shouldBe false
    }
  }

  it should "release ordinary memory after scalar retirement while external work is still busy" in {
    test(new RobEnqueueBarrier(4)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.setvliOwner.valid.poke(false.B)
      dut.io.setvliOwner.bits.poke(0.U)
      dut.io.configPublished.valid.poke(false.B)
      dut.io.configPublished.bits.poke(0.U)
      dut.io.cancel.poke(false.B)
      dut.io.robEmpty.poke(true.B)
      dut.io.evuBusy.poke(true.B)
      dut.io.enqueue.valid.poke(false.B)
      dut.io.enqueue.bits.poke(false.B)
      dut.io.blocked.expect(false.B)
      for (_ <- 0 until 2) {
        dut.io.enqueue.valid.poke(true.B)
        dut.io.enqueue.bits.poke(true.B)
        dut.clock.step()
        dut.io.enqueue.valid.poke(false.B)
        dut.io.robEmpty.poke(false.B)
        dut.clock.step(3)
        dut.io.blocked.expect(true.B) // Preflight/scalar retirement still serialize.
        dut.io.robEmpty.poke(true.B)
        dut.clock.step()
        dut.io.blocked.expect(false.B) // No physical Ack is required to enqueue again.
      }
      // FENCE/configuration retain the original drain requirement.
      dut.io.enqueue.valid.poke(true.B)
      dut.io.enqueue.bits.poke(false.B)
      dut.clock.step()
      dut.io.enqueue.valid.poke(false.B)
      dut.clock.step(4)
      dut.io.blocked.expect(true.B)
      dut.io.evuBusy.poke(false.B)
      dut.io.robEmpty.poke(false.B)
      dut.clock.step()
      dut.io.blocked.expect(true.B)
      dut.io.robEmpty.poke(true.B)
      dut.clock.step()
      dut.io.blocked.expect(false.B)
    }
  }
}
