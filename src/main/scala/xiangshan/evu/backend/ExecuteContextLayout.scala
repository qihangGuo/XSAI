package xiangshan.evu.backend

/** Keep global reduction owners independent of the number of ordinary contexts. */
case class ExecuteContextLayout(ordinary: Int, reductions: Int) {
  require(ordinary >= 1 && reductions >= 0)
  val size: Int = ordinary + reductions
  val dedicated: Boolean = reductions > 0
  def reductionOwner(index: Int): Option[Int] = {
    require(index >= 0 && index < size)
    Option.when(index >= ordinary)(index - ordinary)
  }
}

object ExecuteContextLayout {
  def apply(name: String, interleave: Boolean, ordinary: Int, reductions: Int): ExecuteContextLayout = {
    if (name == "int") ExecuteContextLayout(2, 0)
    else if (interleave && Set("fp_fma", "fp_cmp").contains(name)) {
      ExecuteContextLayout(if (name == "fp_fma") ordinary else 1, reductions)
    } else ExecuteContextLayout(1, 0)
  }
}
