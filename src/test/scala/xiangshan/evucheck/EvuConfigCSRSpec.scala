package xiangshan.evucheck

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.TreadleBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.chipsalliance.cde.config.Parameters
import top.ArgParser
import xiangshan.{ExternalVectorKey, XSCoreParamsKey, XSTileKey}
import xiangshan.backend.decode.{EvuDecoder, XSDecode}
import xiangshan.backend.fu.NewCSR.EvuCSR
import xiangshan.backend.fu.FuConfig
import xiangshan.backend.fu.wrapper.EvuFuncUnit
import xiangshan.evu._

class EvuConfigFuncHarness(implicit p: Parameters) extends Module {
  private val ep = p(ExternalVectorKey)
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val avl = Input(UInt(64.W))
    val vtype = Input(UInt(64.W))
    val valid = Input(Bool())
    val ready = Input(Bool())
    val scalar = Output(UInt(64.W))
    val accepted = Output(Bool())
    val command = Output(new EvuCoreCommand(ep))
  })
  val unit = Module(new EvuFuncUnit(FuConfig.EvuCfg))
  unit.io.in.bits := 0.U.asTypeOf(unit.io.in.bits)
  unit.io.flush := 0.U.asTypeOf(unit.io.flush)
  unit.io.in.valid := io.valid
  unit.io.out.ready := io.ready
  unit.io.in.bits.ctrl.fuOpType := io.instruction(31, 25)
  unit.io.in.bits.ctrl.rfWen.get := true.B
  unit.io.in.bits.data.src(0) := io.avl
  unit.io.in.bits.data.src(1) := io.vtype
  unit.io.in.bits.data.imm := Cat(io.instruction(14, 12), io.instruction(24, 20),
    io.instruction(19, 15), io.instruction(11, 7))
  io.scalar := unit.io.out.bits.res.data
  io.command := unit.io.out.bits.ctrl.amuCtrl.get.data.asTypeOf(new EvuCoreCommand(ep))
  io.accepted := unit.io.out.fire
}

class EvuConfigCSRSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU ABI2 configuration publication"
  it should "carry the same normalized config as the scalar VL result under backpressure" in {
    val (base, _, _) = ArgParser.parse(Array("--config", "DefaultExternalVectorMatrixConfig",
      "--disable-perf", "--disable-alwaysdb"))
    implicit val p: Parameters = base.alterPartial { case XSCoreParamsKey => base(XSTileKey).head }
    test(new EvuConfigFuncHarness).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      dut.io.valid.poke(true.B)
      dut.io.ready.poke(false.B)
      dut.io.avl.poke(((BigInt(1) << 64) - 1).U)
      dut.io.vtype.poke(0x10.U)
      val register = (BigInt(0x46) << 25) | (11 << 20) | (10 << 15) | (7 << 12) | (10 << 7) | 0xb
      dut.io.instruction.poke(register.U)
      for (_ <- 0 until 3) {
        dut.io.accepted.expect(false.B)
        dut.io.scalar.expect(128.U)
        dut.io.command.config.vl.expect(128.U)
        dut.io.command.config.vtype.expect(0x10.U)
        dut.io.command.instr.expect(register.U)
        dut.clock.step()
      }
      dut.io.ready.poke(true.B)
      dut.io.accepted.expect(true.B)
      dut.clock.step()
      // Same scalar register number must not become part of immediate VTYPE.
      val immediate = (BigInt(0x45) << 25) | (15 << 20) | (10 << 15) | (10 << 7) | 0xb
      dut.io.instruction.poke(immediate.U)
      dut.io.avl.poke(13.U)
      dut.io.vtype.poke(((BigInt(1) << 64) - 1).U)
      dut.io.scalar.expect(13.U)
      dut.io.command.config.vtype.expect(15.U)
      dut.io.command.config.vill.expect(false.B)
    }
  }
  it should "decode scalar return only for config and require SETVL funct3=7" in {
    val entries = EvuDecoder.decodeArray.map { case (bits, control) =>
      (bits.value >> 25).toInt -> (bits, control.asInstanceOf[XSDecode])
    }.toMap
    for (f7 <- Seq(0x45, 0x46)) {
      entries(f7)._2.xWen shouldBe true
      entries(f7)._2.noSpec shouldBe true
      entries(f7)._2.blockBack shouldBe true
    }
    entries(0x32)._2.xWen shouldBe false
    (entries(0x46)._1.mask & (BigInt(7) << 12)) shouldBe (BigInt(7) << 12)
    (entries(0x46)._1.value & (BigInt(7) << 12)) shouldBe (BigInt(7) << 12)
    (entries(0x45)._1.mask & (BigInt(7) << 12)) shouldBe BigInt(0)
  }

  it should "publish only accepted normalized config and restore complete EVCFG state" in {
    val ep = EvuParams()
    test(new EvuCSR(ep)).withAnnotations(Seq(TreadleBackendAnnotation)) { dut =>
      def clear(x: Data): Unit = x match {
        case b: Bool => b.poke(false.B)
        case u: UInt => u.poke(0.U)
        case s: SInt => s.poke(0.S)
        case v: Vec[_] => v.foreach(clear)
        case b: Bundle => b.elements.values.foreach(clear)
      }
      clear(dut.io.write); clear(dut.io.accepted); clear(dut.io.completed); clear(dut.io.drain)
      dut.io.busy.poke(false.B)
      val cmd = dut.io.accepted.bits
      cmd.instr.poke(((BigInt(0x46) << 25) | (7 << 12) | 0xb).U)
      cmd.base.poke(13.U)
      cmd.config.valid.poke(true.B)
      cmd.config.configured.poke(true.B)
      cmd.config.vl.poke(13.U)
      cmd.config.vtype.poke(0x0f.U)
      cmd.config.vill.poke(false.B)
      dut.clock.step(2)
      dut.io.configured.expect(false.B)
      dut.io.accepted.valid.poke(true.B)
      dut.clock.step()
      dut.io.accepted.valid.poke(false.B)
      dut.io.config.vl.expect(13.U)
      dut.io.config.vtype.expect(0x0f.U)
      dut.io.configured.expect(true.B)
      val cfg = EvuCSRs.addresses.indexOf(EvuCSRs.cfg)
      val invalid = (BigInt(1) << 63) | (BigInt(1) << 40)
      dut.io.write(cfg).valid.poke(true.B)
      dut.io.write(cfg).data.poke(invalid.U)
      dut.clock.step()
      dut.io.write(cfg).valid.poke(false.B)
      dut.io.read(cfg).expect(invalid.U)
      dut.io.snapshot.length shouldBe 13
      dut.io.snapshot(12).expect(invalid.U)
      dut.io.write(cfg).valid.poke(true.B)
      dut.io.write(cfg).data.poke(0.U)
      dut.clock.step()
      dut.io.write(cfg).valid.poke(false.B)
      dut.io.configured.expect(false.B)
      dut.io.config.vill.expect(false.B)
      dut.io.config.vl.expect(128.U)
      dut.io.config.vtype.expect(0x10.U)
    }
  }
}
