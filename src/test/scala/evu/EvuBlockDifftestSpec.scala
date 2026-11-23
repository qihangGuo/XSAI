package evu

class EvuBlockDifftestSpec extends EvuRvvMathDifftestSpec {
  override def traceEnvironment: String = "EVU_BLOCK_TRACE"
  override def expectedRecords: Int = 12
}
