package xiangshan.evu.area

import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.chipsalliance.cde.config.Parameters
import xiangshan.evu.EvuParams
import xiangshan.evu.adapter.EvuVectorParameters
import xiangshan.evu.common.{VectorIssueStructure, VectorParams, VXSequencerParams}
import xiangshan.evu.exu._

/** Export the production ExecutionUnit boundary, including its existing pipeline and writeback logic. */
object EvuExuMain {
  // Keep aligned with EvuVectorBackend, including effective widths set by EvuVectorParameters.
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
    singleGroupMinMax = ep.singleGroupMinMax,
    alignedSlide = ep.alignedSlide,
    singleGroupSlide = ep.singleGroupSlide,
    crossInstructionPrefetch = ep.crossInstructionPrefetch,
    extendedResultForwarding = ep.extendedResultForwarding,
    shortResultForwarding = ep.shortResultForwarding
  )

  private val includedGroups = Seq("int", "fp_fma", "fp_cmp", "fp_div", "fp_conv")

  def targets(vp: VectorParams): Seq[VXSequencerParams] = {
    val production = vp.issStructure.generate(vp).flatMap(_.seqs)
    val selected = production.filter(s => includedGroups.contains(s.name))
    require(selected.map(_.name).toSet == includedGroups.toSet && selected.size == includedGroups.size,
      "Review changed production ExecutionUnit configuration")
    includedGroups.map(name => selected.find(_.name == name).get)
  }

  private def write(path: Path, text: String): Unit =
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))

  private def strings(values: Seq[String]): String = values.map(s => s"\"$s\"").mkString("[", ", ", "]")

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
    if (listOnly) {
      all.foreach(s => println(s"ExecutionUnit${s.name}"))
      return
    }
    val chosen = if (selection == "all") all else all.filter(s => s"ExecutionUnit${s.name}" == selection)
    require(chosen.nonEmpty, s"Unknown or excluded ExecutionUnit: $selection")
    val output = Paths.get(target).toAbsolutePath.normalize
    chosen.foreach { s =>
      require(!Files.exists(output.resolve(s"ExecutionUnit${s.name}")), "Refusing to overwrite an RTL export")
    }
    implicit val p: Parameters = EvuVectorParameters(
      EvuAreaMain.parameters, ep.vlenBits, ep.dlenBits, ep.mlenBits, vp)
    chosen.foreach { s =>
      val name = s"ExecutionUnit${s.name}"
      val directory = output.resolve(name)
      Files.createDirectories(directory)
      var depth = 0
      var fuNames = Seq.empty[String]
      var fuDepths = Seq.empty[Int]
      var iterative = Seq.empty[String]
      ChiselStage.emitSystemVerilogFile({
        val unit = new ExecutionUnit(s.fus, s.name)
        require(unit.desiredName == name)
        depth = unit.maxPipeDepth
        fuNames = unit.fus.map(_.desiredName)
        fuDepths = unit.fus.map {
          case fu: PipelinedFunctionalUnit => fu.depth
          case _: IterativeFunctionalUnit => 0
        }
        iterative = unit.iter_fus.map(_._1.desiredName)
        unit
      }, args = Array("--target-dir", directory.toString, "--split-verilog"),
        firtoolOpts = Array("-O=release", "--disable-all-randomization", "--strip-debug-info",
          "--lowering-options=disallowLocalVariables,disallowPortDeclSharing,locationInfoStyle=none"))
      val manifest = s"""{
        |  "top": "$name", "scope": "EXECUTION_UNIT_NATIVE_IO", "execution_unit_included": true,
        |  "group": "${s.name}", "fu_names": ${strings(fuNames)},
        |  "fu_pipeline_depths": ${fuDepths.mkString("[", ", ", "]")}, "iterative_fu_names": ${strings(iterative)},
        |  "vlen_bits": ${ep.vlenBits}, "dlen_bits": ${ep.dlenBits}, "mlen_bits": ${ep.mlenBits},
        |  "lanes": ${ep.lanes}, "elen_bits": 32, "vat_bits": ${vp.vatSz},
        |  "pipeline_depth": $depth, "split_fp_issue": ${ep.splitFpIssue},
        |  "segmented_imul": ${vp.useSegmentedIMul}, "segmented_fma": ${vp.useSegmentedFPFMA},
        |  "parallel_fp32_div": ${vp.parallelFp32Div}, "fp32_minmax_tree": ${vp.fp32MinMaxTree},
        |  "single_group_minmax": ${vp.singleGroupMinMax},
        |  "extended_result_forwarding": ${vp.extendedResultForwarding},
        |  "short_result_forwarding": ${vp.shortResultForwarding},
        |  "difftest": false, "debug_vrf": false, "diagnostic_counters": false,
        |  "added_wrapper_registers": false, "vrf_included": false,
        |  "control_predecode": "issue_and_writeback_v1", "mask_reduction_before_selection": true
        |}
        |""".stripMargin
      write(directory.resolve("fu_config.json"), manifest)
      write(directory.resolve("parameters.txt"), s"$ep\n$vp\nProduction group: ${s.name}\nFactories: ${s.fus.mkString(", ")}\n")
      println(s"Exported $name: DLEN=${ep.dlenBits}, FUs=${fuNames.mkString(",")}, maxPipeDepth=$depth -> $directory")
    }
  }
}
