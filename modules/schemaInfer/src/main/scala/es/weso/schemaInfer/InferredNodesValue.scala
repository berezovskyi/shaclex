package es.weso.schemaInfer

sealed abstract class InferredNodesValue {
  def collapse(other: InferredNodesValue): InferredNodesValue
  def freqs: List[Int]
  def number = freqs.max
  def constraint: InferredNodeConstraint
}
private case object NoConstraintNodesValue extends InferredNodesValue {
  override def collapse(other: InferredNodesValue) = other
  override def freqs = List()
  override def constraint = NoConstraint
}
private case class SingleNodesValue(iv: InferredNodeValue) extends InferredNodesValue {
  def collapse(other: InferredNodesValue): InferredNodesValue = {
    ComposedNodesValue(iv.constraint.collapse(other.constraint),iv.number :: freqs)
  }
  def freqs = List(iv.number)
  def constraint = iv.constraint
}

private case class ComposedNodesValue(constraint: InferredNodeConstraint, freqs: List[Int]) extends InferredNodesValue {
  def collapse(other: InferredNodesValue): InferredNodesValue = {
    ComposedNodesValue(constraint.collapse(other.constraint), other.number :: freqs)
  }
}
