package es.weso.shex
import es.weso.shex.shexR.PREFIXES.{sx_start}
import es.weso.rdf.nodes.{BNode, IRI, RDFNode}

abstract sealed trait ShapeLabel {

  def toRDFNode: RDFNode = this match {
    case IRILabel(iri) => iri
    case BNodeLabel(bn) => bn
    case Start => sx_start
  }

  def relativize(base: IRI): ShapeLabel = this match {
    case IRILabel(iri) => IRILabel(iri.relativizeIRI(base))
    case other => other
  }

}

case object Start extends ShapeLabel
case class IRILabel(iri: IRI) extends ShapeLabel
case class BNodeLabel(bnode: BNode) extends ShapeLabel

object ShapeLabel {
  def fromRDFNode(node: RDFNode): Either[String,ShapeLabel] = node match {
    case iri: IRI => Right(IRILabel(iri))
    case bn: BNode => Right(BNodeLabel(bn))
    case _ => Left(s"Cannot convert $node to ShapeLabel")
  }

  def fromString(str: String): Either[String,ShapeLabel] = for {
    node <- RDFNode.fromString(str)
    label <- fromRDFNode(node)
  } yield label

}