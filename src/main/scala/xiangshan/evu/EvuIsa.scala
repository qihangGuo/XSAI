package xiangshan.evu

import chisel3._
import chisel3.util._

class EvuCoreCommand(p: EvuParams) extends Bundle {
  val pc = UInt(64.W)
  val config = new EvuExecutionConfig(p)
  val instr  = UInt(32.W)
  val base   = UInt(64.W)
  val vl     = UInt(log2Ceil(p.vlenBits + 1).W)
  val length = UInt(32.W)
  val aux    = UInt(8.W)
  val pageMap = new EvuPageMap(p)
}

/**
  * The external-vector instruction envelope.
  *
  * The scalar core supplies the already-read rs1 value as `base`; the EVU
  * does not add another scalar register file.  The instruction itself carries
  * the vector register indices and the operation selector.  This keeps the
  * first integration small while leaving a clean handshake for the backend.
  */
class EvuDispatchRequest(p: EvuParams) extends Bundle {
  val pc = UInt(64.W)
  val config = new EvuExecutionConfig(p)
  val instr  = UInt(32.W)
  val base   = UInt(64.W)
  val vl     = UInt(log2Ceil(p.vlenBits + 1).W)
  val length = UInt(32.W)
  val aux    = UInt(8.W)
  val stride = SInt(32.W)
  val chain  = Bool()
  val pageMap = new EvuPageMap(p)
}

object EvuIsa {
  // New FP32 forms reuse the original arithmetic opcodes internally. aux[6]
  // selects element zero, aux[5] a VRF scalar source, aux[4] quant scalar scale.
  val loadE0 = 0x52.U(7.W)
  val storeE0 = 0x53.U(7.W)
  val faddE0 = 0x54.U(7.W)
  val fsubE0 = 0x55.U(7.W)
  val fmulE0 = 0x56.U(7.W)
  val fdivE0 = 0x57.U(7.W)
  val fmaxE0 = 0x58.U(7.W)
  val exp2E0 = 0x59.U(7.W)
  val faddScalar = 0x5a.U(7.W)
  val fsubScalar = 0x5b.U(7.W)
  val fmulScalar = 0x5c.U(7.W)
  val fmaxScalar = 0x5d.U(7.W)
  val fpQuantScalar = 0x5e.U(7.W)
  def isElementZero(f7: UInt): Bool = f7 >= loadE0 && f7 <= exp2E0
  def isScalarSource(f7: UInt): Bool = f7 >= faddScalar && f7 <= fmaxScalar
  def isScalarForm(f7: UInt): Bool = isElementZero(f7) || isScalarSource(f7) || f7 === fpQuantScalar
  def isLoad(f7: UInt): Bool = f7 === load || f7 === loadE0
  def isStore(f7: UInt): Bool = f7 === store || f7 === storeE0
  def canonical(f7: UInt): UInt = MuxLookup(f7, f7)(Seq(
    loadE0 -> load, storeE0 -> store, faddE0 -> fadd, fsubE0 -> fsub,
    fmulE0 -> fmul, fdivE0 -> fdiv, fmaxE0 -> fmax, exp2E0 -> exp2,
    faddScalar -> fadd, fsubScalar -> fsub, fmulScalar -> fmul,
    fmaxScalar -> fmax, fpQuantScalar -> fpQuantToCute))
  // The external EVU is an inference-oriented vector engine.  Its public
  // command format still has a three-bit SEW field, but only byte/halfword/
  // word elements are implemented.  Keeping this limit in one place makes
  // every ingress (custom decoder, vtype configuration and Saturn adapter)
  // agree that SEW=64 is illegal.
  val maxSupportedSew = 2

  def supportsElementWidth(opcode: UInt, sew: UInt): Bool = {
    val widening = opcode === EvuOpcode.WAdd || opcode === EvuOpcode.WSub || opcode === EvuOpcode.WMul
    val fp = Seq(EvuOpcode.FAdd, EvuOpcode.FSub, EvuOpcode.FMul, EvuOpcode.FMacc,
      EvuOpcode.FDiv, EvuOpcode.FMin, EvuOpcode.FMax, EvuOpcode.FReduceSum,
      EvuOpcode.FReduceMin, EvuOpcode.FReduceMax, EvuOpcode.Exp2).map(opcode === _).reduce(_ || _)
    val exp2Width = !fp || sew === 1.U || sew === 2.U
    (!isScalarForm(opcode) || sew === 2.U) &&
      sew <= maxSupportedSew.U && (!widening || sew < maxSupportedSew.U) && exp2Width &&
      (opcode =/= EvuOpcode.FpQuantToCute || sew === 2.U)
  }

  /** Register groups must be checked before the custom command retires. */
  def supportsRegisterGroups(instr: UInt, lmul: UInt): Bool = {
    val rawOp = instr(31, 25)
    val op = canonical(rawOp)
    val memory = op === load || op === store
    val control = op === setvli || op === setvl ||
      op === launch || op === waitOp || op === fence
    val widening = op === wadd || op === wsub || op === wmul
    val reduction = Seq(reduceSum, reduceMax, reduceMin, reduceAnd, reduceOr,
      reduceXor, freduceSum, freduceMin, freduceMax).map(op === _).reduce(_ || _)
    val count = Mux(lmul(2), 1.U(5.W), (1.U(5.W) << lmul(1, 0))(4, 0))
    val destCount = Mux(reduction, 1.U, Mux(widening && !lmul(2), count << 1, count))
    def aligned(reg: UInt, n: UInt): Bool =
      n <= 8.U && (reg & (n - 1.U)) === 0.U && (reg +& n) <= 32.U
    val dest = instr(11, 7)
    val src1 = instr(19, 15)
    val src2 = instr(24, 20)
    // FP quantization's CUTE entry stream covers one physical VRF register.
    (!isScalarForm(rawOp) || lmul === 0.U) && (control || Mux(memory, aligned(dest, count),
      Mux(op === fpQuantToCute, count === 1.U,
        aligned(dest, destCount) && aligned(src2, count) &&
          (op === exp2 || op === gatherImm || aligned(src1, Mux(reduction, 1.U, count))))))
  }

  val custom0 = "b0001011".U(7.W)

  // funct7 values are intentionally private to the XSAI extension.  funct3
  // remains available for SEW, so arithmetic is not restricted to one width.
  val add   = 1.U(7.W)
  val sub   = 2.U(7.W)
  val and   = 3.U(7.W)
  val or    = 4.U(7.W)
  val xor   = 5.U(7.W)
  val mul   = 6.U(7.W)
  val mac   = 7.U(7.W)
  // Retired CONVERT opcode 0x08 remains reserved.
  // Retired VRF DEQUANT opcode 0x09 remains reserved.
  // Retired BIASADD opcode 0x0a remains reserved.
  // Retired VRF REQUANT opcode 0x0b remains reserved.
  val reduceSum = 12.U(7.W)
  val reduceMax = 13.U(7.W)
  val reduceMin = 14.U(7.W)
  val exp2 = 15.U(7.W)
  val sll = 0x20.U(7.W)
  val srl = 0x21.U(7.W)
  val sra = 0x22.U(7.W)
  val min = 0x23.U(7.W)
  val max = 0x24.U(7.W)
  val minu = 0x25.U(7.W)
  val maxu = 0x26.U(7.W)
  val fadd = 0x30.U(7.W)
  val fsub = 0x31.U(7.W)
  val fmul = 0x32.U(7.W)
  val fmacc = 0x33.U(7.W)
  val fdiv = 0x34.U(7.W)
  val fmin = 0x35.U(7.W)
  val fmax = 0x36.U(7.W)
  val freduceSum = 0x37.U(7.W)
  // 0x38 remains reserved; it must decode as an illegal instruction.
  val wadd = 0x39.U(7.W)
  val wsub = 0x3a.U(7.W)
  val wmul = 0x3b.U(7.W)
  val satAdd = 0x3c.U(7.W)
  val satSub = 0x3d.U(7.W)
  val slideUp = 0x3e.U(7.W)
  val slideDown = 0x3f.U(7.W)
  val reduceAnd = 0x40.U(7.W)
  val reduceOr = 0x41.U(7.W)
  val reduceXor = 0x42.U(7.W)
  val freduceMin = 0x43.U(7.W)
  val freduceMax = 0x44.U(7.W)
  val load  = 0x10.U(7.W)
  val store = 0x11.U(7.W)
  // Control operations do not access the EVU VRF. They provide explicit
  // program-visible completion points for a future scalar ROB bridge.
  val launch = 0x18.U(7.W)
  val waitOp = 0x19.U(7.W)
  val fence  = 0x1a.U(7.W)
  val setvli = 0x45.U(7.W)
  val setvl  = 0x46.U(7.W)
  val gather = 0x47.U(7.W)
  val scatter = 0x48.U(7.W)
  // Retired nonlinear opcodes 0x49..0x4b remain reserved.
  val fpQuantToCute = 0x50.U(7.W)
  // rs1 is an unsigned element index, not a scalar or vector register.
  val gatherImm = 0x51.U(7.W)
  // Retired SETMASK opcode 0x4d remains reserved.
  // Retired fixed-point QuantToCute (0x4c) and SETCFG (0x4e) are reserved.
  // Retired VRF QUANT opcode 0x4f remains reserved.

  // custom1 (0101011) is reserved by the existing MATRIX_INSTS ISA.
  def isCustom(instr: UInt): Bool = instr(6, 0) === custom0
  def sew(instr:      UInt): UInt = instr(14, 12)
}

object EvuVtype {
  def configure(p: EvuParams, immediate: Bool, sew: UInt, vl: UInt, aux: UInt): (UInt, UInt, Bool) = {
    val newSew = aux(5, 3)
    val sign = aux(2)
    val magnitude = aux(1, 0)
    val baseVlm = p.vlenBytes.U >> newSew
    val vlm = Mux(sign, baseVlm >> magnitude, baseVlm << magnitude)
    val illegal = newSew > EvuIsa.maxSupportedSew.U || (sign && magnitude === 0.U)
    (Mux(illegal, 0.U, Mux(vl > vlm, vlm, vl)), Mux(immediate, newSew, sew), illegal)
  }
}

class EvuDispatchAdapter(p: EvuParams) extends Module {
  val io = IO(new Bundle {
    val in    = Flipped(Decoupled(new EvuDispatchRequest(p)))
    val out   = Decoupled(new EvuCommand(p))
    val flush = Input(Bool())
  })

  val buffered = Queue(io.in, 1, flush = Some(io.flush))
  io.out.valid   := buffered.valid && !io.flush
  io.out.bits    := 0.U.asTypeOf(new EvuCommand(p))
  buffered.ready := io.out.ready && !io.flush

  val instr = buffered.bits.instr
  val rawF7 = instr(31, 25)
  val f7 = EvuIsa.canonical(rawF7)
  // All EVU operations use a custom opcode and funct7 operation selector.
  // funct3 is copied to sew below, matching the RISC-V convention.
  val opcode = MuxLookup(f7, EvuOpcode.Invalid)(Seq(
    EvuIsa.add    -> EvuOpcode.Add,
    EvuIsa.sub    -> EvuOpcode.Sub,
    EvuIsa.and    -> EvuOpcode.And,
    EvuIsa.or     -> EvuOpcode.Or,
    EvuIsa.xor    -> EvuOpcode.Xor,
    EvuIsa.mul    -> EvuOpcode.Mul,
    EvuIsa.mac    -> EvuOpcode.Mac,
    EvuIsa.reduceSum -> EvuOpcode.ReduceSum,
    EvuIsa.reduceMax -> EvuOpcode.ReduceMax,
    EvuIsa.reduceMin -> EvuOpcode.ReduceMin,
    EvuIsa.exp2 -> EvuOpcode.Exp2,
    EvuIsa.sll -> EvuOpcode.Sll,
    EvuIsa.srl -> EvuOpcode.Srl,
    EvuIsa.sra -> EvuOpcode.Sra,
    EvuIsa.min -> EvuOpcode.Min,
    EvuIsa.max -> EvuOpcode.Max,
    EvuIsa.minu -> EvuOpcode.MinU,
    EvuIsa.maxu -> EvuOpcode.MaxU,
    EvuIsa.fadd -> EvuOpcode.FAdd,
    EvuIsa.fsub -> EvuOpcode.FSub,
    EvuIsa.fmul -> EvuOpcode.FMul,
    EvuIsa.fmacc -> EvuOpcode.FMacc,
    EvuIsa.fdiv -> EvuOpcode.FDiv,
    EvuIsa.fmin -> EvuOpcode.FMin,
    EvuIsa.fmax -> EvuOpcode.FMax,
    EvuIsa.freduceSum -> EvuOpcode.FReduceSum,
    EvuIsa.wadd -> EvuOpcode.WAdd,
    EvuIsa.wsub -> EvuOpcode.WSub,
    EvuIsa.wmul -> EvuOpcode.WMul,
    EvuIsa.satAdd -> EvuOpcode.SatAdd,
    EvuIsa.satSub -> EvuOpcode.SatSub,
    EvuIsa.slideUp -> EvuOpcode.SlideUp,
    EvuIsa.slideDown -> EvuOpcode.SlideDown,
    EvuIsa.reduceAnd -> EvuOpcode.ReduceAnd,
    EvuIsa.reduceOr -> EvuOpcode.ReduceOr,
    EvuIsa.reduceXor -> EvuOpcode.ReduceXor,
    EvuIsa.freduceMin -> EvuOpcode.FReduceMin,
    EvuIsa.freduceMax -> EvuOpcode.FReduceMax,
    EvuIsa.load   -> EvuOpcode.Load,
    EvuIsa.store  -> EvuOpcode.Store,
    EvuIsa.launch -> EvuOpcode.Launch,
    EvuIsa.waitOp -> EvuOpcode.Wait,
    EvuIsa.fence  -> EvuOpcode.Fence,
    EvuIsa.setvli -> EvuOpcode.SetVli,
    EvuIsa.setvl -> EvuOpcode.SetVl,
    EvuIsa.gather -> EvuOpcode.Gather,
    EvuIsa.scatter -> EvuOpcode.Scatter,
    EvuIsa.fpQuantToCute -> EvuOpcode.FpQuantToCute,
    EvuIsa.gatherImm -> EvuOpcode.GatherImm
  ))
  val supportedSew = EvuIsa.supportsElementWidth(opcode, EvuIsa.sew(instr))
  val controlOp = opcode === EvuOpcode.Launch || opcode === EvuOpcode.Wait || opcode === EvuOpcode.Fence ||
    opcode === EvuOpcode.SetVli || opcode === EvuOpcode.SetVl
  val legal = EvuIsa.isCustom(instr) && opcode =/= EvuOpcode.Invalid && (supportedSew || controlOp) &&
    (!EvuIsa.isScalarForm(rawF7) || (p.scalarInstructions.B && EvuIsa.sew(instr) === 2.U))

  io.out.bits.opcode := Mux(legal, opcode, EvuOpcode.Invalid)
  io.out.bits.pc := buffered.bits.pc
  io.out.bits.config := buffered.bits.config
  io.out.bits.sew    := EvuIsa.sew(instr)
  io.out.bits.vd     := instr(11, 7)(p.regIndexWidth - 1, 0)
  io.out.bits.vs1    := instr(19, 15)(p.regIndexWidth - 1, 0)
  io.out.bits.vs2    := instr(24, 20)(p.regIndexWidth - 1, 0)
  io.out.bits.vl     := buffered.bits.vl
  io.out.bits.base   := buffered.bits.base
  io.out.bits.stride := buffered.bits.stride
  io.out.bits.length := buffered.bits.length
  io.out.bits.aux    := buffered.bits.aux |
    Mux(EvuIsa.isElementZero(rawF7), 0x40.U, 0.U) |
    Mux(EvuIsa.isScalarSource(rawF7), 0x20.U, 0.U) |
    Mux(rawF7 === EvuIsa.fpQuantScalar, 0x10.U, 0.U)
  io.out.bits.write  := legal && opcode === EvuOpcode.Store
  io.out.bits.chain  := buffered.bits.chain
  io.out.bits.jobId  := 0.U
  io.out.bits.epoch  := 0.U
  io.out.bits.pageMap := buffered.bits.pageMap
}
