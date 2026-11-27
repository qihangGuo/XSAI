package xiangshan.evu.area

import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu.EvuParams
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common.VectorParams
import xiangshan.evu.exu._

/** Native FU export. Separate directories disambiguate the three FPLowPrecisionFU variants. */
object EvuFuMain {
  def vectorParams(ep: EvuParams): VectorParams = EvuSynthesisConfig.vectorParams(ep)

  def targets(vp: VectorParams): Seq[(String, FunctionalUnitFactory)] = {
    val factories = vp.issStructure.generate(vp).flatMap(_.seqs).flatMap(_.fus)
    val named = factories.map { f =>
      val name = f match {
        case IntegerPipeFactory              => "IntegerPipe"
        case ShiftPipeFactory                => "ShiftPipe"
        case BitwisePipeFactory              => "BitwisePipe"
        case _: MaskUnitFactory              => "MaskUnit"
        case BitmanipPipeFactory             => "BitmanipPipe"
        case PermuteUnitFactory              => "PermuteUnit"
        case IntegerMultiplyFactory(_, true) => "SegmentedMultiplyPipe"
        case SplitFMAFactory(_, true)        => "FPFMAPipe"
        case FPCmpFactory                    => "FPCompPipe"
        case VFExp2Factory                   => "VFExp2FU"
        case FPConvFactory                   => "FPConvPipe"
        case SIMDFPDivSqrtFactory             => "FPDivSqrt"
        case FPXX8Factory                    => "FPXX8Pipe"
        case FPMXFPFactory                   => "FPMXFPPipe"
        case FPBF16ConvertFactory            => "FPBF16Pipe"
        case other => throw new IllegalArgumentException(s"Review changed production FU configuration: $other")
      }
      name -> f
    }
    require(named.size == 15 && named.map(_._1).distinct.size == 15,
      "Expected fifteen production FUs, including VFExp2")
    named
  }

  private def write(path: Path, text: String): Unit =
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))

  def main(args: Array[String]): Unit = {
    var target = "build/evu-fu/rtl"
    var selection = "all"
    var listOnly = false
    var remaining = args.toList
    while (remaining.nonEmpty) {
      remaining match {
        case "--target-dir" :: value :: tail => target = value; remaining = tail
        case "--fu" :: value :: tail         => selection = value; remaining = tail
        case "--list" :: tail                => listOnly = true; remaining = tail
        case _ => throw new IllegalArgumentException(s"Unknown or incomplete FU export option: ${remaining.head}")
      }
    }
    val ep = EvuParams(enable = true, enableDifftest = false)
    val vp = vectorParams(ep)
    val all = targets(vp)
    if (listOnly) { all.foreach { case (name, _) => println(name) }; return }
    val chosen = if (selection == "all") all else all.filter(_._1 == selection)
    require(chosen.nonEmpty, s"Unknown or excluded FU: $selection")
    val output = Paths.get(target).toAbsolutePath.normalize
    chosen.foreach { case (name, _) =>
      require(!Files.exists(output.resolve(name)), s"Refusing to overwrite an RTL export: $name")
    }
    implicit val p: Parameters = EvuVectorParameters(
      EvuAreaMain.parameters, ep.vlenBits, ep.dlenBits, ep.mlenBits, vp)
    chosen.foreach { case (name, factory) =>
      val directory = output.resolve(name)
      Files.createDirectories(directory)
      var depth = 0
      var rtlTop = ""
      var sidebands = Seq.empty[String]
      ChiselStage.emitSystemVerilogFile({
        val fu = factory.generate
        rtlTop = fu.desiredName
        depth = fu match {
          case pipeline: PipelinedFunctionalUnit => pipeline.depth
          case _: IterativeFunctionalUnit       => 0
        }
        sidebands = fu match {
          case f: FPFMAPipe if f.blockPort => Seq("toPack_valid", "toPack_bits", "fromPack")
          case f: FPLowPrecisionFU if f.packInput.nonEmpty => Seq("packInput_valid", "packInput_bits", "packResult")
          case _ => Nil
        }
        fu
      }, args = Array("--target-dir", directory.toString, "--split-verilog"),
        firtoolOpts = Array("-O=release", "--disable-all-randomization", "--strip-debug-info",
          "--lowering-options=disallowLocalVariables,disallowPortDeclSharing,locationInfoStyle=none"))
      val cfg = EvuSynthesisConfig.manifest(ep, vp)
      cfg("top") = name
      cfg("rtl_top") = rtlTop
      cfg("scope") = "FU_ONLY_NATIVE_IO"
      cfg("execution_unit_included") = false
      cfg("factory") = factory.toString
      cfg("pipeline_depth") = depth
      cfg("iterative") = depth == 0
      cfg("pack_sideband_ports") = ujson.Arr.from(sidebands)
      write(directory.resolve("fu_config.json"), ujson.write(cfg, indent = 2) + "\n")
      write(directory.resolve("parameters.txt"), s"$ep\n$vp\nFactory: $factory\n")
      println(s"Exported $name: RTL top=$rtlTop, depth=$depth -> $directory")
    }
  }
}
