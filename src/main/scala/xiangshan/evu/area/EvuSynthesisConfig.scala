package xiangshan.evu.area

import chisel3.util.log2Ceil
import xiangshan.evu.EvuParams
import xiangshan.evu.common.{VectorIssueStructure, VectorParams}

/** Shared export configuration matching EvuTop's unified-command backend. */
object EvuSynthesisConfig {
  val revision = "production_div2_amax_v2"

  def vectorParams(ep: EvuParams): VectorParams = VectorParams.lgvParams.copy(
    dLen = ep.dlenBits,
    mLen = ep.mlenBits,
    vatSz = log2Ceil(ep.commandDepth),
    externalCommandTags = true,
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
    externalConversionOperands = true,
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
    slidePrefetch = ep.slidePrefetch,
    extendedResultForwarding = ep.extendedResultForwarding,
    shortResultForwarding = ep.shortResultForwarding
  )

  def manifest(ep: EvuParams, vp: VectorParams): ujson.Obj = ujson.Obj(
    "export_revision" -> revision,
    "vlen_bits" -> ep.vlenBits, "dlen_bits" -> ep.dlenBits, "mlen_bits" -> ep.mlenBits,
    "lanes" -> ep.lanes, "elen_bits" -> 32, "vat_bits" -> vp.vatSz,
    "command_depth" -> ep.commandDepth, "external_command_tags" -> vp.externalCommandTags,
    "split_fp_issue" -> ep.splitFpIssue,
    "segmented_imul" -> vp.useSegmentedIMul, "segmented_fma" -> vp.useSegmentedFPFMA,
    "parallel_fp32_div" -> vp.parallelFp32Div, "fp32_minmax_tree" -> vp.fp32MinMaxTree,
    "single_group_minmax" -> vp.singleGroupMinMax, "slide_prefetch" -> vp.slidePrefetch,
    "external_conversion_operands" -> vp.externalConversionOperands,
    "fp32_div_two_bits_per_cycle" -> vp.parallelFp32Div,
    "extended_result_forwarding" -> vp.extendedResultForwarding,
    "short_result_forwarding" -> vp.shortResultForwarding,
    "difftest" -> false, "debug_vrf" -> false, "diagnostic_counters" -> false,
    "added_wrapper_registers" -> false, "vrf_included" -> false)
}
