package xiangshan.evu.area

import chisel3._
import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu.EvuParams
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common.{VectorParams, VXSequencerParams}
import xiangshan.evu.exu._

/** Two real EXUs with the same PACK data connection as VectorBackend. No added state. */
class ExecutionUnitPackPair(fmaParams: VXSequencerParams, mxParams: VXSequencerParams)
    (implicit p: Parameters) extends Module {
  val fma = Module(new ExecutionUnit(fmaParams.fus, fmaParams.name))
  val mx = Module(new ExecutionUnit(mxParams.fus, mxParams.name))
  val io = IO(new Bundle {
    val fma = chiselTypeOf(ExecutionUnitPackPair.this.fma.io)
    val mx = chiselTypeOf(ExecutionUnitPackPair.this.mx.io)
    val pipePackSlots = Output(chiselTypeOf(ExecutionUnitPackPair.this.fma.pipePackSlots.get))
  })
  io.fma <> fma.io
  io.mx <> mx.io
  mx.packIn.get := fma.packOut.get
  fma.packBack.get := mx.packedOut.get
  io.pipePackSlots := fma.pipePackSlots.get
}

/** Export production EXU boundaries and an optional connected FMA/MX measurement. */
object EvuExuMain {
  def vectorParams(ep: EvuParams): VectorParams = EvuSynthesisConfig.vectorParams(ep)
  private val includedGroups = Seq("int", "fp_fma", "fp_cmp", "fp_div", "fp_conv", "fp_xx8", "fp_mxfp", "fp_bf16")
  private val pairName = "ExecutionUnitPackPair"

  def targets(vp: VectorParams): Seq[VXSequencerParams] = {
    val production = vp.issStructure.generate(vp).flatMap(_.seqs)
    require(production.map(_.name).toSet == (includedGroups :+ "fp_exp").toSet,
      "Review changed production ExecutionUnit configuration")
    includedGroups.map(name => production.find(_.name == name).get)
  }

  private def write(path: Path, text: String): Unit =
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))

  def main(args: Array[String]): Unit = {
    var target = "build/evu-exu/rtl"
    var selection = "all"
    var listOnly = false
    var remaining = args.toList
    while (remaining.nonEmpty) {
      remaining match {
        case "--target-dir" :: value :: tail => target = value; remaining = tail
        case "--exu" :: value :: tail        => selection = value; remaining = tail
        case "--list" :: tail                => listOnly = true; remaining = tail
        case _ => throw new IllegalArgumentException(s"Unknown or incomplete ExecutionUnit export option: ${remaining.head}")
      }
    }
    val ep = EvuParams(enable = true, enableDifftest = false)
    val vp = vectorParams(ep)
    val all = targets(vp)
    if (listOnly) { all.foreach(s => println(s"ExecutionUnit${s.name}")); return }
    val pairOnly = selection == pairName
    val chosen = if (selection == "all") all else all.filter(s => s"ExecutionUnit${s.name}" == selection)
    require(chosen.nonEmpty || pairOnly, s"Unknown or excluded ExecutionUnit: $selection")
    val output = Paths.get(target).toAbsolutePath.normalize
    val names = if (pairOnly) Seq(pairName) else chosen.map(s => s"ExecutionUnit${s.name}")
    names.foreach(name => require(!Files.exists(output.resolve(name)), s"Refusing to overwrite $name"))
    implicit val p: Parameters = EvuVectorParameters(
      EvuAreaMain.parameters, ep.vlenBits, ep.dlenBits, ep.mlenBits, vp)
    val opts = Array("-O=release", "--disable-all-randomization", "--strip-debug-info",
      "--lowering-options=disallowLocalVariables,disallowPortDeclSharing,locationInfoStyle=none")
    def config(name: String): ujson.Obj = {
      val cfg = EvuSynthesisConfig.manifest(ep, vp)
      cfg("top") = name
      cfg("rtl_top") = name
      cfg("execution_unit_included") = true
      cfg("control_predecode") = "issue_and_writeback_v1"
      cfg("mask_reduction_before_selection") = true
      cfg
    }
    for (s <- chosen) {
      val name = s"ExecutionUnit${s.name}"
      val directory = output.resolve(name)
      Files.createDirectories(directory)
      val cfg = config(name)
      cfg("scope") = "EXECUTION_UNIT_NATIVE_IO"
      cfg("group") = s.name
      ChiselStage.emitSystemVerilogFile({
        val unit = new ExecutionUnit(s.fus, s.name)
        cfg("pipeline_depth") = unit.maxPipeDepth
        cfg("fu_names") = ujson.Arr.from(unit.fus.map(_.desiredName))
        cfg("fu_pipeline_depths") = ujson.Arr.from(unit.fus.map {
          case fu: PipelinedFunctionalUnit => fu.depth
          case _: IterativeFunctionalUnit => 0
        })
        cfg("iterative_fu_names") = ujson.Arr.from(unit.iter_fus.map(_._1.desiredName))
        cfg("amax_sideband_ports") = ujson.Arr.from(
          if (unit.pipeAmaxQuarters.nonEmpty) (0 until unit.maxPipeDepth).flatMap(i =>
            Seq(s"pipeAmaxQuarters_${i}_valid", s"pipeAmaxQuarters_${i}_bits")) else Nil)
        cfg("pack_sideband_ports") = ujson.Arr.from(
          (if (unit.packOut.nonEmpty) Seq("packOut_valid", "packOut_bits", "packBack") ++
            (0 until unit.maxPipeDepth).flatMap(i => Seq(s"pipePackSlots_${i}_valid", s"pipePackSlots_${i}_bits")) else Nil) ++
          (if (unit.packIn.nonEmpty) Seq("packIn_valid", "packIn_bits", "packedOut") else Nil))
        unit
      }, args = Array("--target-dir", directory.toString, "--split-verilog"), firtoolOpts = opts)
      write(directory.resolve("fu_config.json"), ujson.write(cfg, indent = 2) + "\n")
      write(directory.resolve("parameters.txt"), s"$ep\n$vp\nProduction group: ${s.name}\nFactories: ${s.fus.mkString(", ")}\n")
      println(s"Exported $name: maxPipeDepth=${cfg("pipeline_depth")} -> $directory")
    }
    if (pairOnly) {
      val dir = output.resolve(pairName)
      Files.createDirectories(dir)
      val cfg = config(pairName)
      cfg("scope") = "EXECUTION_UNIT_PACK_PAIR_NATIVE_IO"
      cfg("group") = "fp_fma+fp_mxfp"
      cfg("pipeline_depth") = 6
      cfg("fu_names") = ujson.Arr("FPFMAPipe", "FPLowPrecisionFU")
      cfg("fu_pipeline_depths") = ujson.Arr(6, 2)
      cfg("iterative_fu_names") = ujson.Arr()
      cfg("amax_sideband_ports") = ujson.Arr()
      cfg("execution_units") = ujson.Arr("ExecutionUnitfp_fma", "ExecutionUnitfp_mxfp")
      cfg("pack_connection") = "fma.packOut -> mx.packIn; mx.packedOut -> fma.packBack"
      cfg("pack_sideband_ports") = ujson.Arr.from((0 until 6).flatMap(i =>
        Seq(s"io_pipePackSlots_${i}_valid", s"io_pipePackSlots_${i}_bits")))
      ChiselStage.emitSystemVerilogFile(new ExecutionUnitPackPair(
        all.find(_.name == "fp_fma").get, all.find(_.name == "fp_mxfp").get),
        args = Array("--target-dir", dir.toString, "--split-verilog"), firtoolOpts = opts)
      write(dir.resolve("fu_config.json"), ujson.write(cfg, indent = 2) + "\n")
      write(dir.resolve("parameters.txt"), s"$ep\n$vp\nPACK connected pair; overlaps individual FMA/MX area.\n")
      println(s"Exported connected $pairName -> $dir")
    }
  }
}
