package evu

import chisel3._
import chiseltest._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import xiangshan.evu._

import scala.collection.mutable

/** Replay the native NEMU EVU decoder's trace, including complete VRF images,
  * per-instruction flags and stores. NEMU's host test independently bounds the
  * numerical error against long-double exp; this test requires bit equality.
  */
class EvuSoftmaxDifftestSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "EVU online softmax against NEMU"

  it should "match every FP32 instruction, completion flag and store in all seven cases" in {
    val path = sys.env.getOrElse("EVU_SOFTMAX_REFERENCE_TRACE",
      cancel("Generate a trace with NEMU/tools/evu-ref-test and set EVU_SOFTMAX_REFERENCE_TRACE"))
    val source = scala.io.Source.fromFile(path)
    val trace = try source.getLines().map(_.split(" ")).toVector finally source.close()
    trace.size should be(690)
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case freechips.rocketchip.tile.XLen => 64
      case freechips.rocketchip.tile.MaxHartIdBits => 1
      case freechips.rocketchip.rocket.PgLevels => 3
    }
    val ep = EvuParams(enable = true, enableDifftest = true)
    val dutTrace = sys.env.get("EVU_SOFTMAX_DUT_TRACE").map(new java.io.PrintWriter(_))
    try test(LazyModule(new EvuSaturnExp2Shell(ep)).module)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.clock.setTimeout(1000000)
        dut.io.command.valid.poke(false.B)
        dut.io.completion.ready.poke(true.B)
        dut.io.memory.req.ready.poke(true.B)
        dut.io.memory.resp.valid.poke(false.B)
        dut.io.memory.resp.bits.tag.poke(0.U)
        dut.io.memory.resp.bits.data.poke(0.U)
        dut.io.memory.resp.bits.error.poke(false.B)
        dut.io.memory.resp.bits.fault.poke(EvuFault.None)
        dut.io.flush.poke(false.B)
        dut.io.vstart.poke(0.U)
        val memory = mutable.Map.empty[BigInt, BigInt]
        val responses = mutable.Queue.empty[(BigInt, BigInt)]
        var response = Option.empty[(BigInt, BigInt)]
        var completed = Option.empty[Int]
        var expectedStore = Option.empty[BigInt]
        var storeBeats = 0
        val beatMask = (BigInt(1) << ep.mlenBits) - 1
        val byteMask = (BigInt(1) << ep.mlenBytes) - 1

        def tick(): Unit = {
          if (response.isEmpty && responses.nonEmpty) response = Some(responses.dequeue())
          dut.io.memory.resp.valid.poke(response.nonEmpty.B)
          response.foreach { case (tag, data) =>
            dut.io.memory.resp.bits.tag.poke(tag.U)
            dut.io.memory.resp.bits.data.poke(data.U)
          }
          val taken = response.nonEmpty && dut.io.memory.resp.ready.peek().litToBoolean
          if (dut.io.memory.req.valid.peek().litToBoolean) {
            val request = dut.io.memory.req.bits
            val address = request.addr.peek().litValue
            val data = if (request.write.peek().litToBoolean) {
              val offset = (address - 0x1000).toInt
              offset should be >= 0
              offset should be < ep.vlenBytes
              offset % ep.mlenBytes should be(0)
              request.mask.expect(byteMask.U)
              val expected = (expectedStore.get >> (offset * 8)) & beatMask
              request.data.expect(expected.U)
              storeBeats += 1
              BigInt(0)
            } else memory(address)
            responses.enqueue((request.tag.peek().litValue, data))
          }
          if (dut.io.completion.valid.peek().litToBoolean) {
            dut.io.completion.bits.exception.expect(false.B)
            dut.io.completion.bits.vxsat.expect(false.B)
            completed = Some(dut.io.completion.bits.fflags.peek().litValue.toInt)
          }
          dut.clock.step()
          if (taken) response = None
        }
        def read(reg: Int): BigInt = (0 until ep.vlenBits / ep.dlenBits).foldLeft(BigInt(0)) { (bits, eg) =>
          bits | (dut.io.vrf(reg * (ep.vlenBits / ep.dlenBits) + eg).peek().litValue << (eg * ep.dlenBits))
        }

        for ((fields, index) <- trace.zipWithIndex) {
          val opcode = Integer.parseInt(fields(0), 16)
          val sew = fields(1).toInt
          val vd = fields(2).toInt
          val flags = Integer.parseInt(fields(5), 16)
          fields.length should be(134)
          sew should be(2)
          val expected = fields.drop(6).zipWithIndex.foldLeft(BigInt(0)) {
            case (bits, (word, lane)) => bits | (BigInt(word, 16) << (32 * lane))
          }
          if (opcode == 0x10) {
            for (offset <- 0 until ep.vlenBytes by ep.mlenBytes)
              memory(BigInt(0x1000 + offset)) = (expected >> (offset * 8)) & beatMask
          }
          expectedStore = if (opcode == 0x11) Some(expected) else None
          storeBeats = 0
          completed = None
          val c = dut.io.command.bits
          c.opcode.poke(opcode.U)
          c.vd.poke(vd.U)
          c.vs1.poke(fields(3).toInt.U)
          c.vs2.poke(fields(4).toInt.U)
          c.sew.poke(sew.U)
          c.vl.poke(128.U)
          c.base.poke(0x1000.U)
          c.stride.poke(0.S)
          c.length.poke(ep.vlenBytes.U)
          c.aux.poke(0.U)
          c.write.poke((opcode == 0x11).B)
          c.chain.poke(false.B)
          c.jobId.poke((index % ep.commandDepth).U)
          c.epoch.poke(0.U)
          c.pageMap.enabled.poke(false.B)
          c.pageMap.base.poke(0.U)
          c.pageMap.length.poke(0.U)
          c.pageMap.ppn.foreach(_.poke(0.U))
          dut.io.command.valid.poke(true.B)
          var waited = 0
          while (!dut.io.command.ready.peek().litToBoolean && waited < 10000) { tick(); waited += 1 }
          dut.io.command.ready.expect(true.B)
          tick()
          dut.io.command.valid.poke(false.B)
          waited = 0
          while (completed.isEmpty && waited < 10000) { tick(); waited += 1 }
          withClue(s"instruction=$index opcode=0x${fields(0)} vd=$vd: ") {
            completed should be(Some(flags))
            // EvuTop publishes its idle snapshot on the cycle after completion;
            // let the VRF's staged load write reach SRAM and the debug shadow.
            tick()
            val actual = read(vd)
            dutTrace.foreach { writer =>
              val words = (0 until 128).map(lane => ((actual >> (32 * lane)) & 0xffffffffL).toLong)
              writer.println(fields.take(5).mkString(" ") + f" ${completed.get}%02x " +
                words.map(word => f"$word%08x").mkString(" "))
            }
            for (lane <- 0 until 128) {
              withClue(s"element=$lane: ") {
                ((actual >> (32 * lane)) & 0xffffffffL) should be((expected >> (32 * lane)) & 0xffffffffL)
              }
            }
            if (opcode == 0x11) storeBeats should be(ep.vlenBytes / ep.mlenBytes)
          }
        }
        responses should be(empty)
        response should be(empty)
        println(s"EVU/NEMU softmax: ${trace.size} instructions, 112 vector stores, all VRF bits and completion flags match")
      } finally dutTrace.foreach(_.close())
  }
}
