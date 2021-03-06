package es.weso.shex
import es.weso.depgraphs.DepGraph
import es.weso.rdf.nodes._
import es.weso.rdf.PREFIXES._
import es.weso.rdf._
import es.weso.rdf.jena.RDFAsJenaModel
import es.weso.shex.shexR.{ RDF2ShEx, ShEx2RDF }
import cats.implicits._

import util._

case class Schema(prefixes: Option[PrefixMap],
                  base: Option[IRI],
                  startActs: Option[List[SemAct]],
                  start: Option[ShapeExpr],
                  shapes: Option[List[ShapeExpr]],
                  tripleExprMap: Option[Map[ShapeLabel,TripleExpr]],
                  imports: List[IRI]
                 ) {

  def resolveShapeLabel(l: ShapeLabel): Either[String, IRI] = l match {
    case IRILabel(iri) => Right(iri)
    case _ => Left(s"Label $l can't be converted to IRI")
  }

  lazy val prefixMap: PrefixMap =
    prefixes.getOrElse(PrefixMap.empty)

  def qualify(node: RDFNode): String =
    prefixMap.qualify(node)

  def qualify(label: ShapeLabel): String =
    prefixMap.qualify(label.toRDFNode)

  // TODO: Convert to Either[String,ShapeExpr]
  def getShape(label: ShapeLabel): Option[ShapeExpr] =
    shapes.getOrElse(List()).find(_.id == Some(label))

  lazy val shapeList = shapes.getOrElse(List())

  def labels: List[ShapeLabel] = {
    shapeList.map(_.id).flatten
  }

  def negCycles: Either[String, Set[Set[ShapeLabel]]] =
    Dependencies.negCycles(this)

  def depGraph: Either[String, DepGraph[ShapeLabel]] =
    Dependencies.depGraph(this)

}

abstract sealed trait ShapeExpr {
  def id: Option[ShapeLabel]
  def addId(lbl: ShapeLabel): ShapeExpr

  def showPrefixMap(pm: PrefixMap) = {
    import es.weso.shex.compact.CompactShow._
    showShapeExpr(this, pm)
  }
}

object ShapeExpr {
  def any: ShapeExpr = Shape.empty

  def fail: ShapeExpr = NodeConstraint.valueSet(List(), List())
}

case class ShapeOr(id: Option[ShapeLabel], shapeExprs: List[ShapeExpr]) extends ShapeExpr {
  def addId(lbl: ShapeLabel) = this.copy(id = Some(lbl))

}

case class ShapeAnd(id: Option[ShapeLabel], shapeExprs: List[ShapeExpr]) extends ShapeExpr {
  def addId(lbl: ShapeLabel) = this.copy(id = Some(lbl))
}

case class ShapeNot(id: Option[ShapeLabel], shapeExpr: ShapeExpr) extends ShapeExpr {
  def addId(lbl: ShapeLabel) = this.copy(id = Some(lbl))
}

case class NodeConstraint(
  id: Option[ShapeLabel],
  nodeKind: Option[NodeKind],
  datatype: Option[IRI],
  xsFacets: List[XsFacet],
  values: Option[List[ValueSetValue]]) extends ShapeExpr {
  override def addId(lbl: ShapeLabel) = this.copy(id = Some(lbl))

}

object NodeConstraint {

  def empty = NodeConstraint(
    id = None,
    nodeKind = None,
    datatype = None,
    xsFacets = List(),
    values = None)

  def nodeKind(nk: NodeKind, facets: List[XsFacet]): NodeConstraint =
    NodeConstraint.empty.copy(
      nodeKind = Some(nk),
      xsFacets = facets)

  def nodeKind(idLabel: Option[ShapeLabel], nk: NodeKind, facets: List[XsFacet]): NodeConstraint =
    NodeConstraint.empty.copy(
      id = idLabel,
      nodeKind = Some(nk),
      xsFacets = facets)

  def datatype(
    dt: IRI,
    facets: List[XsFacet]): NodeConstraint =
    NodeConstraint.empty.copy(
      datatype = Some(dt),
      xsFacets = facets)

  def valueSet(
    vs: List[ValueSetValue],
    facets: List[XsFacet]): NodeConstraint =
    NodeConstraint.empty.copy(
      values = Some(vs),
      xsFacets = facets)
}

case class Shape(
  id: Option[ShapeLabel],
  virtual: Option[Boolean],
  closed: Option[Boolean],
  extra: Option[List[IRI]], // TODO: Extend extras to handle Paths?
  expression: Option[TripleExpr],
  inherit: Option[ShapeLabel],
  semActs: Option[List[SemAct]],
  annotations: Option[List[Annotation]]) extends ShapeExpr {
  def addId(lbl: ShapeLabel) = this.copy(id = Some(lbl))

  def isVirtual: Boolean =
    virtual.getOrElse(Shape.defaultVirtual)

  def isClosed: Boolean =
    closed.getOrElse(Shape.defaultClosed)

  // Converts IRIs to direct paths
  def extraPaths =
    extra.getOrElse(List()).map(Direct(_))

  def isEmpty: Boolean = this == Shape.empty

  // def tripleExpr = expression.getOrElse(TripleExpr.any)

}

object Shape {
  def empty: Shape = Shape(
    id = None,
    virtual = None,
    closed = None,
    extra = None,
    expression = None,
    inherit = None,
    semActs = None,
    annotations = None
  )

  def defaultVirtual = false
  def defaultClosed = false
  def defaultExtra = List[IRI]()
  def defaultInherit = List[ShapeLabel]()
  def defaultSemActs = List[SemAct]()

  def expr(te: TripleExpr): Shape = {
    Shape.empty.copy(expression = Some(te))
  }
}

case class ShapeRef(reference: ShapeLabel) extends ShapeExpr {
  def id = None
  def addId(lbl: ShapeLabel) = this

}

case class ShapeExternal(id: Option[ShapeLabel]) extends ShapeExpr {
  def addId(lbl: ShapeLabel) = this.copy(id = Some(lbl))

}

sealed trait XsFacet {
  val fieldName: String
}

sealed trait StringFacet extends XsFacet

case class Length(v: Int) extends StringFacet {
  val fieldName = "length"
}

case class MinLength(v: Int) extends StringFacet {
  val fieldName = "minlength"
}

case class MaxLength(v: Int) extends StringFacet {
  val fieldName = "maxlength"
}

case class Pattern(p: String, flags: Option[String]) extends StringFacet {
  val fieldName = "pattern"
}

sealed trait NumericFacet extends XsFacet

case class MinInclusive(n: NumericLiteral) extends NumericFacet {
  val fieldName = "mininclusive"
}
case class MinExclusive(n: NumericLiteral) extends NumericFacet {
  val fieldName = "minexclusive"
}
case class MaxInclusive(n: NumericLiteral) extends NumericFacet {
  val fieldName = "maxinclusive"
}
case class MaxExclusive(n: NumericLiteral) extends NumericFacet {
  val fieldName = "maxexclusive"
}
case class TotalDigits(n: Int) extends NumericFacet {
  val fieldName = "totaldigits"
}
case class FractionDigits(n: Int) extends NumericFacet {
  val fieldName = "fractiondigits"
}

sealed trait NumericLiteral
case class NumericInt(n: Int) extends NumericLiteral
case class NumericDouble(n: Double, repr: String) extends NumericLiteral
case class NumericDecimal(n: BigDecimal, repr: String) extends NumericLiteral

sealed trait ValueSetValue

sealed trait ObjectValue extends ValueSetValue

case class IRIValue(i: IRI) extends ObjectValue

sealed trait ObjectLiteral extends ObjectValue
case class StringValue(s: String) extends ObjectLiteral
case class DatatypeString(s: String, iri: IRI) extends ObjectLiteral
case class LangString(s: String, lang: Lang) extends ObjectLiteral

object ObjectValue {
  def trueValue: ObjectValue = DatatypeString("true", xsd_boolean)
  def falseValue: ObjectValue = DatatypeString("false", xsd_boolean)
  def intValue(n: Int): ObjectValue =
    DatatypeString(n.toString, xsd_integer)
  def doubleValue(d: Double, repr: String): ObjectValue =
    DatatypeString(repr, xsd_double)
  def decimalValue(d: BigDecimal, repr: String): ObjectValue =
    DatatypeString(repr, xsd_decimal)
  def literalValue(l: Literal): ObjectValue =
    l match {
      case DatatypeLiteral(lex, dt) => DatatypeString(lex, dt)
      case IntegerLiteral(n) => intValue(n)
      case DecimalLiteral(d) => decimalValue(d, d.toString)
      case DoubleLiteral(d) => doubleValue(d, d.toString)
      case StringLiteral(s) => DatatypeString(s, xsd_string)
      case BooleanLiteral(b) => if (b) trueValue else falseValue
      case LangLiteral(lex, lang) => LangString(lex, lang)
    }
}

case class IRIStem(stem: IRI) extends ValueSetValue
case class IRIStemRange(stem: IRIStemRangeValue,
                        exclusions: Option[List[IRIExclusion]]) extends ValueSetValue

sealed trait IRIStemRangeValue
case class IRIStemValueIRI(iri: IRI) extends IRIStemRangeValue
case class IRIStemWildcard() extends IRIStemRangeValue

sealed trait IRIExclusion
case class IRIRefExclusion(iri: IRI) extends IRIExclusion
case class IRIStemExclusion(iriStem: IRIStem) extends IRIExclusion

case class LanguageStem(stem: Lang) extends ValueSetValue
case class LanguageStemRange(stem: LanguageStemRangeValue,
                        exclusions: Option[List[LanguageExclusion]]) extends ValueSetValue


sealed trait LanguageStemRangeValue
case class LanguageStemRangeLang(stem: Lang) extends LanguageStemRangeValue
case class LanguageStemRangeWildcard() extends LanguageStemRangeValue

sealed trait LanguageExclusion
case class LanguageTagExclusion(lang: Lang) extends LanguageExclusion
case class LanguageStemExclusion(languageStem: LanguageStem) extends LanguageExclusion

case class LiteralStem(stem: String) extends ValueSetValue
case class LiteralStemRange(stem: LiteralStemRangeValue,
                            exclusions: Option[List[LiteralExclusion]]) extends ValueSetValue


sealed trait LiteralStemRangeValue
case class LiteralStemRangeString(str: String) extends LiteralStemRangeValue
case class LiteralStemRangeWildcard() extends LiteralStemRangeValue

sealed trait LiteralExclusion
case class LiteralStringExclusion(str: String) extends LiteralExclusion
case class LiteralStemExclusion(literalStem: LiteralStem) extends LiteralExclusion

case class Language(languageTag: Lang) extends ValueSetValue

case class SemAct(name: IRI, code: Option[String])

abstract sealed trait TripleExpr {
  def addId(label: ShapeLabel): TripleExpr
}

case class EachOf(
  id: Option[ShapeLabel],
  expressions: List[TripleExpr],
  optMin: Option[Int],
  optMax: Option[Max],
  semActs: Option[List[SemAct]],
  annotations: Option[List[Annotation]]) extends TripleExpr {
  lazy val min = optMin.getOrElse(Cardinality.defaultMin)
  lazy val max = optMax.getOrElse(Cardinality.defaultMax)
  override def addId(lbl: ShapeLabel) = this.copy(id = Some(lbl))
}

case class OneOf(
  id: Option[ShapeLabel],
  expressions: List[TripleExpr],
  optMin: Option[Int],
  optMax: Option[Max],
  semActs: Option[List[SemAct]],
  annotations: Option[List[Annotation]]) extends TripleExpr {
  lazy val min = optMin.getOrElse(Cardinality.defaultMin)
  lazy val max = optMax.getOrElse(Cardinality.defaultMax)
  override def addId(lbl: ShapeLabel) = this.copy(id = Some(lbl))
}

case class Inclusion(include: ShapeLabel) extends TripleExpr {
  override def addId(lbl: ShapeLabel) = this // Inclusions have no label
}

case class TripleConstraint(
  id: Option[ShapeLabel],
  optInverse: Option[Boolean],
  optNegated: Option[Boolean],
  predicate: IRI,
  valueExpr: Option[ShapeExpr],
  optMin: Option[Int],
  optMax: Option[Max],
  semActs: Option[List[SemAct]],
  annotations: Option[List[Annotation]]) extends TripleExpr {
  lazy val inverse = optInverse.getOrElse(false)
  lazy val direct = !inverse
  lazy val negated = optNegated.getOrElse(false)
  lazy val min = optMin.getOrElse(Cardinality.defaultMin)
  lazy val max = optMax.getOrElse(Cardinality.defaultMax)
  lazy val path: Path =
    if (direct) Direct(predicate)
    else Inverse(predicate)
  override def addId(lbl: ShapeLabel) = this.copy(id = Some(lbl))
}

object TripleConstraint {
  def emptyPred(pred: IRI): TripleConstraint =
    TripleConstraint(
      None, None, None, pred, None, None, None, None, None)

  def valueExpr(pred: IRI, ve: ShapeExpr): TripleConstraint =
    emptyPred(pred).copy(valueExpr = Some(ve))

  def datatype(pred: IRI, iri: IRI, facets: List[XsFacet]): TripleConstraint =
    emptyPred(pred).copy(valueExpr = Some(NodeConstraint.datatype(iri, facets)))
}

case class Annotation(predicate: IRI, obj: ObjectValue)

object Cardinality {
  lazy val defaultMin = 1
  lazy val defaultMax = IntMax(1)

  def isDefault(min: Int, max: Max): Boolean = {
    min == defaultMin && max == defaultMax
  }
}

abstract sealed trait Max {
  def show = this match {
    case IntMax(v) => v.toString
    case Star => "*"
  }

  def biggerThanOrEqual(x: Int) = this match {
    case IntMax(v) => v >= x
    case Star => true
  }
}
case object Star extends Max
case class IntMax(v: Int) extends Max

sealed trait NodeKind
case object IRIKind extends NodeKind
case object BNodeKind extends NodeKind
case object NonLiteralKind extends NodeKind
case object LiteralKind extends NodeKind

abstract sealed trait ShapeLabel {
  def toRDFNode: RDFNode = this match {
    case IRILabel(iri) => iri
    case BNodeLabel(bn) => bn
  }
}
case class IRILabel(iri: IRI) extends ShapeLabel
case class BNodeLabel(bnode: BNode) extends ShapeLabel

object Schema {

  lazy val rdfDataFormats = RDFAsJenaModel.availableFormats.map(_.toUpperCase)

  def empty: Schema =
    Schema(None, None, None, None, None, None, List())

  def fromString(
    cs: CharSequence,
    format: String,
    base: Option[String] = None): Either[String, Schema] = {
    val formatUpperCase = format.toUpperCase
    formatUpperCase match {
      case "SHEXC" => {
        import compact.Parser.parseSchema
        parseSchema(cs.toString, base)
      }
      case "SHEXJ" => {
        import io.circe.parser._
        import es.weso.shex.implicits.decoderShEx._
        decode[Schema](cs.toString).leftMap(_.getMessage)
      }
      case _ if (rdfDataFormats.contains(formatUpperCase)) => for {
        rdf <- RDFAsJenaModel.fromChars(cs, formatUpperCase, base)
        schema <- RDF2ShEx.rdf2Schema(rdf)
      } yield schema

      case _ => Left(s"Not implemented ShEx parser for format $format")
    }
  }

  def serialize(schema: Schema, format: String): Either[String,String] = {
    val formatUpperCase = format.toUpperCase
    formatUpperCase match {
      case "SHEXC" => {
        import compact.CompactShow._
        Right(showSchema(schema))
      }
      case "SHEXJ" => {
        import io.circe.syntax._
        import es.weso.shex.implicits.encoderShEx._
        Right(schema.asJson.spaces2)
      }
      case _ if (rdfDataFormats.contains(formatUpperCase)) => {
        val model = ShEx2RDF.shEx2Model(schema, None)
        val rdf = RDFAsJenaModel(model)
        rdf.serialize(formatUpperCase)
      }
      case _ =>
        Left(s"Not implemented conversion to $format. Schema: $schema")
    }
  }
}
