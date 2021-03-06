package es.weso.shex.validator
import cats._
import com.typesafe.scalalogging.LazyLogging
import implicits._
import es.weso.rdf.nodes._
import es.weso.rdf.PREFIXES._
import es.weso.shex.ViolationError._
import es.weso.shex._
import es.weso.shex.validator.ShExChecker._

case class ValueChecker(schema: Schema)
  extends ShowValidator(schema) with LazyLogging {

  def checkValue(
    attempt: Attempt,
    node: RDFNode)(value: ValueSetValue): CheckTyping = {
    logger.info(s"checkValue: $node $value")
    value match {
      case IRIValue(iri) => node match {
        case i: IRI =>
          checkCond(iri == i, attempt,
            msgErr(s"${node.show} != ${i.show}"),
            s"${node.show} == ${i.show}")
        case _ => errStr(s"${node.show} != ${iri.show}")
      }
      case StringValue(s) => node match {
        case l: Literal => checkCond(s == l.getLexicalForm && l.dataType == xsd_string, attempt,
          msgErr(s"${node.show} != ${l}"),
          s"${node.show} == ${l}")
        case _ => errStr(s"${node.show} != ${value}")
      }
      case DatatypeString(s, iri) => node match {
        case l: Literal =>
          checkCond(s == l.getLexicalForm && iri == l.dataType, attempt,
            msgErr(s"${node.show} != ${l}"),
            s"${node.show} == ${l}")
        case _ => errStr(s"${node.show} != ${value}")
      }
      case LangString(s, lang) => {
        node match {
          case LangLiteral(str, l) =>
            checkCond(s == str && lang == l, attempt,
              msgErr(s"${node.show} != ${value}"),
              s"${node.show} == ${value}")
          case _ => errStr(s"${node.show} != ${value}")
        }
      }
      case LanguageStem(stem) => node match {
        case LangLiteral(x,lang) => checkCond(stem.lang.startsWith(lang.lang), attempt,
          msgErr(s"${node.show} lang($lang) does not match ${stem}"),
          s"${node.show} lang($lang) matches ${stem}")
        case _ => errStr(s"${node.show} is not a language tagged literal")
      }
      case Language(langTag) => {
        node match {
          case LangLiteral(x,Lang(lang)) => checkCond(langTag.lang === lang, attempt,
            msgErr(s"${node.show} lang($lang) does not match ${langTag}"),
            s"${node.show} lang($lang) matches ${langTag}")
          case _ => errStr(s"${node.show} is not a language tagged literal")
        }
      }
      case IRIStem(stem) => node match {
        case i: IRI =>
          checkCond(i.getLexicalForm.startsWith(stem.getLexicalForm), attempt,
            msgErr(s"${node.show} does not match stem ${stem.show}"),
            s"${node.show} matches with stem ${stem.show}")
        case _ => errStr(s"${node.show} must be an IRI to match with IRI stem ${stem.show}")
      }
      case IRIStemRange(stem, exclusions) => errStr(s"Not implemented stem range: $stem $exclusions")

      case _ => {
        logger.error(s"Not implemented checkValue: $value")
        errStr(s"Not implemented checkValue: $value")
      }
    }
  }
}