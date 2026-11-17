package xiangshan.evu.area

import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu.EvuParams
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common.{VectorIssueStructure, VectorParams}
import xiangshan.evu.exu._

/** Export native FU boundaries, without an ExecutionUnit or extra pipeline registers. */
object EvuFuMain {
  // Match EvuVectorBackend's EVU-to-Saturn translation. No production RTL is changed.
  def vectorParams(ep: EvuParams): VectorParams = VectorParams.lgvParams.copy(
    dLen = ep.dlenBits,
    mLen = ep.mlenBits,
    vdqEntries = ep.dispatchDepth,
    vlifqEntries = ep.loadResponseDepth max ep.maxMemOutstanding,
    vsifqEntries = ep.maxMemOutstanding,
    vlrobEntries = ep.loadResponseDepth,
    vlissqEntries = ep.loadIssueDepth,
    vsissqEntries = ep.storeIssueDepth,
    vxissqEntries = ep.executeIssueDepth,
    vpissqEntries = ep.specialIssueDepth,
    vrfBanking = ep.vrfBanks,
    debugVrf = false,
    diagnosticCounters = false,
    enableChaining = true,
    enableOOO = true,
    pipelinedVatClear = true,
    issStructure = if (ep.splitFpIssue) VectorIssueStructure.SplitFP else VectorParams.lgvParams.issStructure,
    reductionContexts = ep.reductionContexts,
    fmaOrdinaryContexts = ep.fmaOrdinaryContexts,
    parallelFp32Div = ep.parallelFp32Div,
    interleaveReductions = ep.interleaveReductions,
    fp32MinMaxTree = ep.fp32MinMaxTree,
    alignedSlide = ep.alignedSlide,
    singleGroupSlide = ep.singleGroupSlide,
    crossInstructionPrefetch = ep.crossInstructionPrefetch,
    extendedResultForwarding = ep.extendedResultForwarding,
    shortResultForwarding = ep.shortResultForwarding
  )

  def targets(vp: VectorParams): Seq[(String, FunctionalUnitFactory)] = {
    val factories = vp.issStructure.generate(vp).flatMap(_.seqs).flatMap(_.fus)
    val selected = factories.filterNot(f => f == VFExp2Factory || f == FPXX8Factory || f == FPMXFPFactory)
    val named = selected.map { f =>
      val name = f match {
        case IntegerPipeFactory              => "IntegerPipe"
        case ShiftPipeFactory                => "ShiftPipe"
        case BitwisePipeFactory              => "BitwisePipe"
        case _: MaskUnitFactory              => "MaskUnit"
        case BitmanipPipeFactory             => "BitmanipPipe"
        case PermuteUnitFactory              => "PermuteUnit"
        case IntegerMultiplyFactory(_, true) => "SegmentedMultiplyPipe"
        case SIMDFPFMAFactory(_, true)       => "FPFMAPipe"
        case FPCmpFactory                    => "FPCompPipe"
        case FPConvFactory                   => "FPConvPipe"
        case SIMDFPDivSqrtFactory             => "FPDivSqrt"
        case other => throw new IllegalArgumentException(s"Review changed production FU configuration: $other")
      }
      name -> f
    }
    require(named.size == 11 && named.map(_._1).distinct.size == 11, "Expected eleven distinct production FUs")
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
    if (listOnly) {
      all.foreach { case (name, _) => println(name) }
      return
    }
    val chosen = if (selection == "all") all else all.filter(_._1 == selection)
    require(chosen.nonEmpty, s"Unknown or excluded FU: $selection")
    val output = Paths.get(target).toAbsolutePath.normalize
    chosen.foreach { case (name, _) =>
      require(!Files.exists(output.resolve(name)), s"Refusing to overwrite an RTL export: ${output.resolve(name)}")
    }
    implicit val p: Parameters = EvuVectorParameters(
      EvuAreaMain.parameters, ep.vlenBits, ep.dlenBits, ep.mlenBits, vp)
    chosen.foreach { case (name, factory) =>
      val directory = output.resolve(name)
      Files.createDirectories(directory)
      var depth = 0
      ChiselStage.emitSystemVerilogFile({
        val fu = factory.generate
        require(fu.desiredName == name, s"Unexpected FU name: ${fu.desiredName}")
        depth = fu match {
          case pipeline: PipelinedFunctionalUnit => pipeline.depth
          case _: IterativeFunctionalUnit       => 0
        }
        fu
      }, args = Array("--target-dir", directory.toString, "--split-verilog"),
        firtoolOpts = Array("-O=release", "--disable-all-randomization", "--strip-debug-info",
          "--lowering-options=disallowLocalVariables,disallowPortDeclSharing,locationInfoStyle=none"))
      val manifest = s"""{
        |  "top": "$name", "scope": "FU_ONLY_NATIVE_IO", "execution_unit_included": false,
        |  "vlen_bits": ${ep.vlenBits}, "dlen_bits": ${ep.dlenBits}, "mlen_bits": ${ep.mlenBits},
        |  "lanes": ${ep.lanes}, "elen_bits": 32, "vat_bits": ${vp.vatSz},
        |  "pipeline_depth": $depth, "iterative": ${depth == 0},
        |  "segmented_imul": ${vp.useSegmentedIMul}, "segmented_fma": ${vp.useSegmentedFPFMA},
        |  "parallel_fp32_div": ${vp.parallelFp32Div}, "fp32_minmax_tree": ${vp.fp32MinMaxTree},
        |  "difftest": false, "debug_vrf": false
        |}
        |""".stripMargin
      write(directory.resolve("fu_config.json"), manifest)
      write(directory.resolve("parameters.txt"), s"$ep\n$vp\nFactory: $factory\n")
      println(s"Exported $name: DLEN=${ep.dlenBits}, native FU IO, depth=$depth -> $directory")
    }
  }
}
