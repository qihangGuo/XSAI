package evu

class EvuLowPrecisionDifftestSpec extends EvuRvvMathDifftestSpec {
  override def traceEnvironment: String = "EVU_LOW_PRECISION_TRACE"
  override def expectedRecords: Int = 198
}
