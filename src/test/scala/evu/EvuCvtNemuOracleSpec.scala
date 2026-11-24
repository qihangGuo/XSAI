package evu

import org.scalatest.flatspec.AnyFlatSpec
import scala.io.Source
import scala.util.Using

/** Software-only check of the reference library; no Chisel elaboration or simulator. */
class EvuCvtNemuOracleSpec extends AnyFlatSpec {
  "NEMU packed conversions" should "match the independent numeric models, flags and tails" in {
    val path = sys.env.getOrElse("EVU_LOW_PRECISION_TRACE", cancel("Set EVU_LOW_PRECISION_TRACE"))
    val regBytes = 512
    val lengths = Seq(0, 1, 2, 15, 16, 17, 63, 64, 65, 127, 128)
    val seen = scala.collection.mutable.Set.empty[(Int, Int, Int)]
    def littleEndian(bytes: Array[Byte]): BigInt = BigInt(1, bytes.reverse)
    Using.resource(Source.fromFile(path)) { lines =>
      for (line <- lines.getLines()) {
        val fields = line.split(" ")
        val f7 = (java.lang.Long.parseLong(fields(0), 16) >>> 25).toInt
        val op = EvuCvtTestOps.op(f7)
        val vl = fields(2).toInt
        assert(fields(1) == "16" && fields(4) == "0" && lengths.contains(vl))
        val bytes = fields.drop(5).map(Integer.parseInt(_, 16).toByte)
        assert(bytes.length == 10 * regBytes)
        val src = littleEndian(bytes.take(regBytes))
        val scale = bytes(4 * regBytes) & 255
        val actual = bytes.drop(6 * regBytes)
        val activeBytes = (vl * op.destinationBits + 7) / 8
        var packed = BigInt(0)
        var flags = 0
        for (i <- 0 until vl) {
          val input = (src >> (i * op.sourceBits)) & ((BigInt(1) << op.sourceBits) - 1)
          val (value, raised) = op.result(input, scale)
          packed |= value << (i * op.destinationBits)
          flags |= raised
        }
        withClue(s"${op.name} VL=$vl scale=$scale: ") {
          assert(seen.add((f7, vl, scale)))
          assert(flags == fields(3).toInt)
          assert(littleEndian(actual.take(activeBytes)) == packed)
          assert(actual.drop(activeBytes).forall(_ == 0xa5.toByte))
        }
      }
    }
    val expected = for (op <- EvuCvtTestOps.ops; vl <- lengths; scale <- Seq(127, 128, 255))
      yield (op.f7, vl, scale)
    assert(seen.toSet == expected.toSet)
  }
}
