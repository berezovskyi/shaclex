package es.weso.shex

import java.nio.file.{Files, Paths}
import cats.implicits._
import es.weso.depgraphs.DepGraph
import es.weso.rdf.{PrefixMap, RDFBuilder, RDFReader}
import es.weso.rdf.nodes.{IRI, RDFNode}
import es.weso.shex.shexR.{RDF2ShEx, ShEx2RDF}
import es.weso.utils.UriUtils._
import scala.io.Source
import scala.util.{Either, Left, Right, Try}

case class Schema(id: IRI,
                  prefixes: Option[PrefixMap],
                  base: Option[IRI],
                  startActs: Option[List[SemAct]],
                  start: Option[ShapeExpr],
                  shapes: Option[List[ShapeExpr]],
                  tripleExprMap: Option[Map[ShapeLabel,TripleExpr]],
                  imports: List[IRI]
                 ) {

  def addShape(se: ShapeExpr): Schema = this.copy(shapes = addToOptionList(se,shapes))

  private def addToOptionList[A](x: A, maybeLs: Option[List[A]]): Option[List[A]] = maybeLs match {
    case None => Some(List(x))
    case Some(xs) => Some(x :: xs)
  }

  def getTripleExpr(lbl: ShapeLabel): Either[String,TripleExpr] = for {
   maybeTem <- eitherResolvedTripleExprMap
   te <- maybeTem match {
     case None => Left(s"Not found $lbl because there is no tripleExpr map")
     case Some(tem) => tem.get(lbl) match {
       case None => Left(s"Not found label $lbl in tripleExprMap. Available labels: ${tem.keySet.mkString(",")}")
       case Some(te) => Right(te)
     }
   }
  } yield te

  def resolveShapeLabel(l: ShapeLabel): Either[String, IRI] = l match {
    case IRILabel(iri) => Right(iri)
    case _ => Left(s"Label $l can't be converted to IRI")
  }

  lazy val prefixMap: PrefixMap =
    prefixes.getOrElse(PrefixMap.empty)

  def getTripleExprMap(): Map[ShapeLabel, TripleExpr] =
    eitherResolvedTripleExprMap match {
      case Right(tem) => tem.getOrElse(Map())
      case Left(e) => {
        println(s"Error: $e")
        Map()
      }
  }

  lazy val localShapesMap: Map[ShapeLabel,ShapeExpr] = {
    shapes match {
      case None => Map()
      case Some(ls) => {
        ls.collect{ case s if s.id.isDefined => (s.id.get, s)}.toMap
      }
    }
  }

  lazy val eitherMapsImported: Either[String,MapsToImport] = {
    closureImports(imports, List(id), MapsToImport(localShapesMap,tripleExprMap))
  }

  lazy val eitherResolvedShapesMap: Either[String,Map[ShapeLabel,ShapeExpr]] = {
    eitherMapsImported.map(_.shapesExpr)
  }

  lazy val eitherResolvedTripleExprMap: Either[String,Option[Map[ShapeLabel,TripleExpr]]] = {
    eitherMapsImported.map(_.maybeTripleExprs)
  }


  case class MapsToImport(shapesExpr: Map[ShapeLabel,ShapeExpr], maybeTripleExprs: Option[Map[ShapeLabel,TripleExpr]]) {
    def merge(schema: Schema): MapsToImport = {
      this.copy(
        shapesExpr = schema.localShapesMap ++ shapesExpr,
        maybeTripleExprs = schema.tripleExprMap match {
          case None => maybeTripleExprs
          case Some(otherTripleExprsMap) => maybeTripleExprs match {
            case None => Some(otherTripleExprsMap)
            case Some(tripleExprsMap) => Some(otherTripleExprsMap ++ tripleExprsMap)
          }
       }
      )
    }
  }

  // TODO: make the following method tailrecursive
  private def closureImports(imports: List[IRI],
                             visited: List[IRI],
                             current: MapsToImport
                            ): Either[String, MapsToImport] = imports match {
    case Nil => Right(current)
    case (i::is) => if (visited contains i) closureImports(is,visited,current)
    else for {
      schema <- Schema.fromIRI(i,base)
      sm <- closureImports(is ++ schema.imports, i :: visited, current.merge(schema))
    } yield sm
  }

  def addId(i: IRI): Schema = this.copy(id = i)

  def qualify(node: RDFNode): String =
    prefixMap.qualify(node)

  def qualify(label: ShapeLabel): String =
    prefixMap.qualify(label.toRDFNode)

  def getShape(label: ShapeLabel): Either[String,ShapeExpr] = for {
    sm <- eitherResolvedShapesMap
    se <- sm.get(label) match {
      case None => Left(s"Not found $label in schema. Available labels: ${sm.keySet.mkString}")
      case Some(se) => Right(se)
    }
  } yield se

  lazy val localShapes: List[ShapeExpr] = shapes.getOrElse(List())

  lazy val shapeList: List[ShapeExpr] = // shapes.getOrElse(List())
   eitherResolvedShapesMap.fold(_ => localShapes,
     sm => sm.values.toList)

  def labels: List[ShapeLabel] = {
    eitherResolvedShapesMap.fold(
      e => localShapes.map(_.id).flatten,
      sm => sm.keySet.toList)
  }

  def addTripleExprMap(te: Map[ShapeLabel,TripleExpr]): Schema =
    this.copy(tripleExprMap = Some(te))

  def oddNegCycles: Either[String,Set[Set[(ShapeLabel,ShapeLabel)]]] =
    Dependencies.oddNegCycles(this)

  def negCycles: Either[String, Set[Set[(ShapeLabel,ShapeLabel)]]] =
    Dependencies.negCycles(this)

  def depGraph: Either[String, DepGraph[ShapeLabel]] =
    Dependencies.depGraph(this)

  def showCycles(str: Either[String,Set[Set[(ShapeLabel,ShapeLabel)]]]): String = str match {
    case Left(e) => e
    case Right(ss) => ss.map(s => s.map(_.toString).mkString(",")).mkString("\n")
  }
/*  lazy val listNegCycles: List[String] = negCycles.fold(
    e => List(e),
    ns => if (ns.isEmpty) List()
    else
      List(s"Negative cycles found: [${ns.map(s => s.map(_.toString).mkString(",")).mkString(",")}]")
  ) */


 private def checkShapeLabel(lbl: ShapeLabel): Either[String, Unit] = for {
   se <- getShape(lbl)
   refs <- se.getShapeRefs(this).map(getShape(_)).sequence
  } yield {
    // println(s"Label: $lbl, refs: ${se.getShapeRefs(this).mkString(",")}")
    // println(s"References: ${refs.mkString(",")}")
    ()
  } 

  private lazy val checkBadShapeLabels: Either[String,Unit] = for {
    shapesMap <- eitherResolvedShapesMap
    //_ <- { println(s"shapesMap: $shapesMap"); Right(())}
    _ <- shapesMap.keySet.toList.map(lbl => checkShapeLabel(lbl)).sequence
  } yield (()) 


  private lazy val checkOddNegCycles: Either[String, Unit] = {
    println(s"OddNegCycles: $oddNegCycles")
    oddNegCycles match {
      case Left(e) => Left(e)
      case Right(cs) => if (cs.isEmpty) Right(())
      else
        Left(s"Negative cycles: ${showCycles(oddNegCycles)}")
    }
  }

  lazy val wellFormed: Either[String,Unit] = for {
    _ <- checkOddNegCycles
    _ <- checkBadShapeLabels
  } yield (())

  def relativize(maybeBase: Option[IRI]): Schema = maybeBase match {
    case None => this
    case Some(baseIri) => Schema(
      id.relativizeIRI(baseIri),
      prefixes,
      base.map(_.relativizeIRI(baseIri)),
      startActs,
      start.map(_.relativize(baseIri)),
      shapes.map(_.map(_.relativize(baseIri))),
      tripleExprMap,
      imports
    )
  }

}


object Schema {

  def rdfDataFormats(rdfReader: RDFReader) = rdfReader.availableParseFormats.map(_.toUpperCase)

  def empty: Schema =
    Schema(IRI(""),None, None, None, None, None, None, List())

  def fromIRI(i: IRI, base: Option[IRI]): Either[String, Schema] = {
    println(s"fromIRI: $i")
    Try {
      val uri = i.uri
      if (uri.getScheme == "file") {
        if (Files.exists(Paths.get(i.uri))) {
            val str = Source.fromURI(uri).mkString
            fromString(str, "ShExC", Some(i)).map(schema => schema.addId(i))
        } else {
          val iriShEx = i + ".shex"
          if (Files.exists(Paths.get((iriShEx).uri))) {
            val str = Source.fromURI(iriShEx.uri).mkString
            fromString(str, "ShExC", Some(i)).map(schema => schema.addId(i))
          } else {
            val iriJson = i + ".json"
            if (Files.exists(Paths.get((iriJson).uri))) {
            val str = Source.fromURI(iriJson.uri).mkString
            fromString(str, "JSON", Some(i)).map(schema => schema.addId(i))
           }
           else {
            println(s"File $i does not exist")
            Left(s"File $i does not exist")
           }
        }}}
      else
       for {
         schema <- getSchemaWithExts(i, List(("","ShExC"),("shex","ShExC"), ("json", "JSON")),base)
       } yield schema.addId(i)
    }.fold(exc => Left(s"Error obtaining schema from IRI($i):${exc.getMessage}"), identity)
  }

  private def getSchemaWithExts(iri: IRI, exts: List[(String,String)], base: Option[IRI] ): Either[String, Schema] = exts match {
    case (e :: es) => getSchemaExt(iri,e,base) orElse getSchemaWithExts(iri,es,base)
    case Nil => Left(s"Can not obtain schema from iri: $iri")
  }

  private def getSchemaExt(iri: IRI, pair: (String,String), base: Option[IRI]): Either[String,Schema] = {
   val (ext,format) = pair
   val uri = if (ext == "") iri.uri
   else (iri + "." + ext).uri
   val r = for {
    str <- derefUri(uri)
    schema <- Schema.fromString(str,format,base,None)
   } yield schema
   println(s"getSchemaExt($iri,$pair): $r")
   r
  }

  /**
  * Reads a Schema from a char sequence
    * @param cs char sequence
    * @param format syntax format
    * @param base base URL
    * @param maybeRDFReader RDFReader value from which to obtain RDF data formats (in case of RDF format)
    * @return either a Schema or a String message error
    */
  def fromString(cs: CharSequence,
                 format: String = "ShExC",
                 base: Option[IRI] = None,
                 maybeRDFReader: Option[RDFReader] = None
                ): Either[String, Schema] = {
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
      case _ => maybeRDFReader match {
        case None => Left(s"Not implemented ShEx parser for format $format and no rdfReader provided")
        case Some(rdfReader) =>
         if (rdfDataFormats(rdfReader).contains(formatUpperCase)) for {
          rdf    <- rdfReader.fromString(cs, formatUpperCase, base)
          schema <- RDF2ShEx.rdf2Schema(rdf)
         } yield schema
         else Left(s"Not implemented ShEx parser for format $format")
       }
    }
  }
  def serialize(schema: Schema,
                format: String,
                base: Option[IRI],
                rdfBuilder: RDFBuilder): Either[String,String] = {
    val formatUpperCase = format.toUpperCase
    val relativeSchema = schema.relativize(base)
    formatUpperCase match {
      case "SHEXC" => {
        import compact.CompactShow._
        Right(showSchema(relativeSchema))
      }
      case "SHEXJ" => {
        import io.circe.syntax._
        import es.weso.shex.implicits.encoderShEx._
        Right(relativeSchema.asJson.spaces2)
      }
      case _ if (rdfDataFormats(rdfBuilder).contains(formatUpperCase)) => {
        val rdf = ShEx2RDF(relativeSchema, None, rdfBuilder.empty)
        rdf.serialize(formatUpperCase, base)
      }
      case _ =>
        Left(s"Not implemented conversion to $format. Schema: $schema")
    }
  }

}
