package es.weso.shex.validator

import cats.implicits._
import es.weso.shapeMaps.ResultShapeMap

case class Result(e: Either[ShExError, ResultShapeMap]) {

  def toEitherS: Either[String, ResultShapeMap] = e.leftMap(_.toString)
  def toEither: Either[ShExError,ResultShapeMap] = e

}