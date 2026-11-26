package xiangshan.backend.fu.wrapper

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import xiangshan._
import xiangshan.backend.fu.{FuConfig, PipedFuncUnit}
import xiangshan.backend.fu.matrix.Bundles.AmuCtrlIO
import xiangshan.evu.{EvuConfigRules, EvuCoreCommand, EvuIsa}

class EvuFuncUnit(cfg: FuConfig)(implicit p: Parameters) extends PipedFuncUnit(cfg) {
  protected val in = io.in.bits
  protected val out = io.out.bits
  private val ep = p(ExternalVectorKey)
  private val imm = in.data.imm

  connect0LatencyCtrlSingal
  io.out.valid := io.in.valid
  io.in.ready := io.out.ready

  val payload = Wire(new EvuCoreCommand(ep))
  payload := 0.U.asTypeOf(payload)
  require(payload.getWidth <= out.ctrl.amuCtrl.get.data.getWidth,
    "EVU command must fit the AMU retirement payload")
  payload.instr := Cat(in.ctrl.fuOpType(6, 0), imm(14, 10), imm(9, 5),
    imm(17, 15), imm(4, 0), EvuIsa.custom0)
  payload.base := in.data.src(0)
  // The PC memory supplies the fetch-block base; ftqOffset is in halfwords.
  val instructionPc = in.data.pc.get + (in.ctrl.ftqOffset.get << instOffsetBits)
  payload.pc := utility.SignExt(instructionPc, 64)
  payload.pageMap := 0.U.asTypeOf(payload.pageMap)
  val configInstruction = in.ctrl.fuOpType(6, 0) === EvuIsa.setvli || in.ctrl.fuOpType(6, 0) === EvuIsa.setvl
  val requestedType = EvuConfigRules.requestedType(payload.instr, in.data.src(1))
  val normalized = EvuConfigRules.normalize(ep, in.data.src(0), requestedType)
  when(configInstruction) {
    payload.config := normalized
  }
  payload.vl := EvuIsa.cpuCommandVl(ep, payload.instr, normalized.vl)
  payload.length := ep.vlenBytes.U
  payload.aux := Mux(configInstruction, requestedType(7, 0), 0.U)

  out.res.data := Mux(configInstruction, normalized.vl, 0.U)
  out.ctrl.exceptionVec.foreach(_ := 0.U.asTypeOf(out.ctrl.exceptionVec.get))
  out.ctrl.amuCtrl.get.evu := true.B
  out.ctrl.amuCtrl.get.op := AmuCtrlIO.arithOp()
  out.ctrl.amuCtrl.get.data := payload.asUInt
  if (env.EnableDifftest) {
    out.ctrl.amuCtrl.get.pc.get := DontCare
    out.ctrl.amuCtrl.get.coreid.get := DontCare
  }
}
