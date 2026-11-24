package evu

class EvuLowPrecisionDifftestSpec extends EvuRvvMathDifftestSpec {
  override def traceEnvironment: String = "EVU_LOW_PRECISION_TRACE"
  override def expectedRecords: Int = 18 * 3 * 11 // opcodes, scale/input patterns, VL cases
}
