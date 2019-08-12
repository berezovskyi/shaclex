package es.weso.shacl.manifest

import com.typesafe.scalalogging.LazyLogging
import es.weso.rdf._
import es.weso.rdf.jena.RDFAsJenaModel
import es.weso.rdf.nodes._
import es.weso.rdf.parser.RDFParser
import es.weso.utils.FileUtils._
import ManifestPrefixes._
import es.weso.utils.EitherUtils._
import scala.util._

case class RDF2Manifest(base: Option[IRI],
                        derefIncludes: Boolean) extends RDFParser with LazyLogging {

  def rdf2Manifest(rdf: RDFReader, visited: List[RDFNode] = List()): Either[String, List[Manifest]] =
    for {
      candidates <- rdf.subjectsWithType(mf_Manifest)
      ns         <- parseNodes(candidates.toList, manifest(List()))(rdf)
    } yield ns

  def manifest(visited: List[IRI]): RDFParser[Manifest] = { (n, rdf) =>
    for {
      maybeLabel   <- stringFromPredicateOptional(rdfs_label)(n, rdf)
      maybeComment <- stringFromPredicateOptional(rdfs_comment)(n, rdf)
      entries      <- entries(n, rdf)
      includes     <- includes(visited)(n, rdf)
    } yield {
      Manifest(label = maybeLabel, comment = maybeComment, entries = entries.toList, includes = includes)
    }
  }

  def entries: RDFParser[Seq[Entry]] =
    parsePropertyList(mf_entries, entry)

  def getEntryType(node: RDFNode): Either[String, EntryType] = {
    node match {
      case `sht_Validate`            => Right(Validate)
      case `sht_ValidationFailure`   => Right(ValidationFailure)
      case `sht_MatchNodeShape`      => Right(MatchNodeShape)
      case `sht_WellFormedSchema`    => Right(WellFormedSchema)
      case `sht_NonWellFormedSchema` => Right(NonWellFormedSchema)
      case `sht_ConvertSchemaSyntax` => Right(ConvertSchemaSyntax)
      case _                         => Left("Unexpected entry type: " + node)
    }
  }

  def entry: RDFParser[Entry] = { (n, rdf) =>
    for {
      entryTypeUri <- rdfType(n, rdf)
      entryType    <- getEntryType(entryTypeUri)
      maybeName    <- stringFromPredicateOptional(mf_name)(n, rdf)
      actionNode   <- objectFromPredicate(mf_action)(n, rdf)
      action       <- action(actionNode, rdf)
      resultNode   <- objectFromPredicate(mf_result)(n, rdf)
      result       <- result(resultNode, rdf)
      statusIri    <- iriFromPredicate(mf_status)(n, rdf)
      specRef      <- optional(iriFromPredicate(sht_specRef))(n, rdf)
    } yield
      Entry(node = n,
            entryType = entryType,
            name = maybeName,
            action = action,
            result = result,
            status = Status(statusIri),
            specRef = specRef)
  }

  def iriDataFormat2str(iri: IRI): Either[String, String] = {
    iri match {
      case `sht_TURTLE` => parseOk("TURTLE")
      case _            => parseFail("Unexpected schema format: " + iri)
    }
  }

  def iriSchemaFormat2str(iri: IRI): Either[String, String] = {
    iri match {
      case `sht_SHACLC` => parseOk("SHACLC")
      case `sht_TURTLE` => parseOk("TURTLE")
      case _            => parseFail("Unexpected schema format: " + iri)
    }
  }

  def action: RDFParser[ManifestAction] = { (n, rdf) =>
    for {
      data <- optional(iriFromPredicate(sht_dataGraph))(n, rdf)
      schema <- {
        iriFromPredicateOptional(sht_shapesGraph)(n, rdf)
      }
      dataFormatIri      <- optional(iriFromPredicate(sht_data_format))(n, rdf)
      dataFormat         <- mapOptional(dataFormatIri, iriDataFormat2str)
      schemaFormatIRI    <- optional(iriFromPredicate(sht_schema_format))(n, rdf)
      schemaFormat       <- mapOptional(schemaFormatIRI, iriSchemaFormat2str)
      schemaOutputFormat <- optional(iriFromPredicate(sht_schema_output_format))(n, rdf)
      triggerMode        <- optional(iriFromPredicate(sht_triggerMode))(n, rdf)
      node               <- optional(oneOfPredicates(Seq(sht_node, sht_focus)))(n, rdf)
      shape              <- optional(iriFromPredicate(sht_shape))(n, rdf)
    } yield
      ManifestAction(
        schema = schema,
        schemaFormat = schemaFormat,
        data = data,
        dataFormat = dataFormat,
        triggerMode = triggerMode,
        schemaOutputFormat = schemaOutputFormat,
        node = node,
        shape = shape
      )
  }

  def result: RDFParser[Result] = { (n, rdf) => {
      n match {
        case BooleanLiteral(b) => Right(BooleanResult(b))
        case iri: IRI =>
          if (noType(iri, rdf)) Right(IRIResult(iri))
          else compoundResult(iri, rdf)
        case bNode: BNode => compoundResult(bNode, rdf)
        case _            => parseFail("Unexpected type of result " + n)
      }
    }
  }

  def compoundResult: RDFParser[Result] = (n, rdf) => {
    iriFromPredicate(rdf_type)(n, rdf) match {
      case Right(iri) =>
        iri match {
          case `sh_ValidationReport` =>
            for {
              report <- validationReport(n, rdf)
            } yield ReportResult(report)
          case _ => parseFail(s"Unsupporte type of compound result: $iri")
        }
      case Left(e) => parseFail(s"compoundResult. Wrong rdf:type of node: $n: $e")
    }
  }

  /* def validatedPairs: RDFParser[Set[ValidPair]] =
    parsePropertyValues(sht_pair, parsePair)

  def parsePair: RDFParser[ValidPair] = (n, rdf) => for {
    node <- objectFromPredicate(sht_node)(n, rdf)
    shape <- objectFromPredicate(sht_shape)(n, rdf)
  } yield ValidPair(node, shape) */

  def validationReport: RDFParser[ValidationReport] =
    (n, rdf) => parsePropertyValues(sh_result, violationError)(n, rdf).map(ValidationReport(_))

  def violationError: RDFParser[ViolationError] =
    (n, rdf) =>
      for {
        errorType   <- optional(iriFromPredicate(rdf_type))(n, rdf)
        focusNode   <- optional(objectFromPredicate(sh_focusNode))(n, rdf)
        path        <- optional(iriFromPredicate(sh_path))(n, rdf)
        severity    <- optional(iriFromPredicate(sh_severity))(n, rdf)
        scc         <- optional(iriFromPredicate(sh_sourceConstraintComponent))(n, rdf)
        sourceShape <- optional(iriFromPredicate(sh_sourceShape))(n, rdf)
        value       <- optional(objectFromPredicate(sh_value))(n, rdf)
      } yield {
        ViolationError(errorType, focusNode, path, severity, scc, sourceShape, value)
    }

  def noType(n: RDFNode, rdf: RDFReader): Boolean = {
    val types = objectsFromPredicate(rdf_type)(n, rdf)
    types.getOrElse(Set()).isEmpty
  }

  def includes(visited: List[RDFNode]): RDFParser[List[(RDFNode, Option[Manifest])]] = { (n, rdf) => {
      for {
        includes <- {
          objectsFromPredicate(mf_include)(n, rdf)
        }
        result <- {
          val ds: List[Either[String, (IRI, Option[Manifest])]] =
            includes.toList.map(iri => derefInclude(iri, base, iri +: visited))
          sequence(ds)
        }
      } yield result
    }
  }

  /* TODO: The following code doesn't take into account possible loops */
  def derefInclude(node: RDFNode, base: Option[IRI], visited: List[RDFNode],
  ): Either[String, (IRI, Option[Manifest])] = node match {
    case iri: IRI =>
      if (derefIncludes) {
        val iriResolved = base.fold(iri)(base => base.resolve(iri))
        for {
          rdf <- RDFAsJenaModel.fromURI(iriResolved.getLexicalForm, "TURTLE", Some(iriResolved))
          mfs <- RDF2Manifest(Some(iriResolved), true).rdf2Manifest(rdf, iri +: visited)
          manifest <- if (mfs.size == 1) Right(mfs.head)
          else Left(s"More than one manifests found: ${mfs} at iri $iri")
        } yield (iri, Some(manifest))
      } else Right((iri, None))
    case _ => Left(s"Trying to deref an include from node $node which is not an IRI")
  }

  def parsePropertyValue[A](pred: IRI, parser: RDFParser[A]): RDFParser[A] =
    (n, rdf) =>
      for {
        value  <- objectFromPredicate(pred)(n, rdf)
        result <- parser(value, rdf)
      } yield result

  def parsePropertyValues[A](pred: IRI, parser: RDFParser[A]): RDFParser[Set[A]] =
    (n, rdf) =>
      for {
        values  <- objectsFromPredicate(pred)(n, rdf)
        results <- parseNodes(values.toList, parser)(rdf)
      } yield results.toSet

  def parsePropertyList[A](pred: IRI, parser: RDFParser[A]): RDFParser[List[A]] =
    (n, rdf) =>
      for {
        ls <- rdfListForPredicateAllowingNone(pred)(n, rdf)
        vs <- parseNodes(ls, parser)(rdf)
      } yield vs

  def mapOptional[A, B](optA: Option[A], fn: A => Either[String, B]): Either[String, Option[B]] = {
    optA match {
      case None => parseOk(None)
      case Some(x) => {
        fn(x) match {
          case Right(v) => Right(Some(v))
          case Left(e)  => Left(e)
        }
      }
    }
  }

  def oneOfPredicates(predicates: Seq[IRI]): RDFParser[IRI] = {
    val ps = predicates.map(iriFromPredicate(_))
    oneOf(ps)
  }

  /**
   * Override this method to provide more info
   */
  override def objectFromPredicate(p: IRI): RDFParser[RDFNode] = { (n, rdf) =>
    for {
      ts <- rdf.triplesWithSubjectPredicate(n, p)
      r <- ts.size match {
        case 0 =>
          parseFail(
            s"objectFromPredicate: Not found triples with subject $n and predicate $p \nRDF: ${rdf.serialize("TURTLE")}")
        case 1 => parseOk(ts.head.obj)
        case _ => parseFail("objectFromPredicate: More than one value from predicate " + p + " on node " + n)
      }
    } yield r

  }
}

object RDF2Manifest extends LazyLogging {

  def read(fileName: String,
           format: String,
           base: Option[String],
           derefIncludes: Boolean
          ): Either[String, (Manifest,RDFBuilder)] = {
    for {
      cs <- getContents(fileName)
      iriBase <- base match {
        case None => Right(None)
        case Some(str) => IRI.fromString(str).map(Some(_))
      }
      rdf <- RDFAsJenaModel.fromChars(cs, format, iriBase).map(_.normalizeBNodes)
      manifest <- fromRDF(rdf, iriBase, derefIncludes)
    } yield (manifest,rdf)
  }

  def fromRDF(rdf: RDFReader, base: Option[IRI], derefIncludes: Boolean): Either[String, Manifest] = {
    RDF2Manifest(base,derefIncludes).rdf2Manifest(rdf).
      flatMap(takeSingle(_,"Number of manifests != 1"))
  }

}
