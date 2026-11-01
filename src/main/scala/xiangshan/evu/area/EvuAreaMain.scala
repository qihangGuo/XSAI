package xiangshan.evu.area

import circt.stage.ChiselStage
import freechips.rocketchip.diplomacy.{DisableMonitors, LazyModule}
import org.chipsalliance.cde.config.Parameters
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import xiangshan.evu.EvuParams

/** Explicit server-side RTL export. Unit tests use emitCHIRRTL only. */
object EvuAreaMain {
  def parameters: Parameters = Parameters.empty.alterPartial {
    case freechips.rocketchip.tile.XLen => 64
    case freechips.rocketchip.tile.MaxHartIdBits => 6
    case freechips.rocketchip.rocket.PgLevels => 3
  }

  def main(args: Array[String]): Unit = {
    var target = "build/evu-area/rtl"
    var bridge = false
    var keepPerf = false
    var addressBits = 48
    var tileDim = 8
    var remaining = args.toList
    while (remaining.nonEmpty) {
      remaining match {
        case "--target-dir" :: value :: tail => target = value; remaining = tail
        case "--with-bridge" :: tail => bridge = true; remaining = tail
        case "--keep-perf" :: tail => keepPerf = true; remaining = tail
        case "--address-bits" :: value :: tail => addressBits = value.toInt; remaining = tail
        case "--tile-dim" :: value :: tail => tileDim = value.toInt; remaining = tail
        case _ => throw new IllegalArgumentException(s"Unknown or incomplete area option: ${remaining.head}")
      }
    }
    val ep = EvuParams(enable = true, enableDifftest = false)
    val output = Paths.get(target).toAbsolutePath.normalize
    Files.createDirectories(output)
    implicit val p: Parameters = parameters
    val top = DisableMonitors { p =>
      LazyModule(new EvuAreaTop(ep, bridge, keepPerf, addressBits, tileDim)(p))
    }
    ChiselStage.emitSystemVerilogFile(top.module,
      args = Array("--target-dir", output.toString, "--split-verilog"),
      firtoolOpts = Array("-O=release", "--disable-all-randomization", "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPortDeclSharing,locationInfoStyle=none"))
    val manifest = s"""{
      |  "top": "EvuAreaTop", "with_bridge": $bridge, "tile_dim": $tileDim,
      |  "keep_perf": $keepPerf, "difftest": false, "debug_vrf": false,
      |  "address_bits": $addressBits, "vlen_bits": ${ep.vlenBits}, "dlen_bits": ${ep.dlenBits},
      |  "mlen_bits": ${ep.mlenBits}, "lanes": ${ep.lanes}, "banks_per_lane": ${ep.vrfBanks},
      |  "vrf_banks": ${ep.lanes * ep.vrfBanks}, "vrf_bank_depth": ${ep.vectorRegs * ep.vlenBits / ep.dlenBits / ep.vrfBanks},
      |  "vrf_bank_width": ${ep.laneBits}, "vrf_mask_granularity": 8,
      |  "vrf_bits": ${ep.vectorRegs * ep.vlenBits}, "vrf_ports": "1RW",
      |  "command_depth": ${ep.commandDepth}, "completion_depth": ${ep.completionDepth},
      |  "reduction_contexts": ${ep.reductionContexts}, "quant_command_depth": ${ep.quantCommandDepth},
      |  "eg_chaining": ${ep.egChaining}, "vmu_line_coalescing": ${ep.vmuLineCoalescing},
      |  "split_fp_issue": ${ep.splitFpIssue}, "scalar_instructions": ${ep.scalarInstructions},
      |  "interleave_reductions": ${ep.interleaveReductions}, "cdc": false,
      |  "sram_area": "NOT_INCLUDED_PENDING_MACRO", "scope": "EVU_ONLY_NOT_SOC_INCREMENT"
      |}
      |""".stripMargin
    Files.write(output.resolve("area_config.json"), manifest.getBytes(StandardCharsets.US_ASCII))
    println(s"EVU-only SystemVerilog exported to $output; SRAM area is pending macro mapping")
  }
}
