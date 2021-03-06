package es.weso.converter
import cats._, data._

trait Converter {

  type Err = String

  type Result[A] = ValidatedNel[Err, A]

  def ok[A](x: A): Result[A] =
    Validated.valid(x)

  def err[A](msg: String): Result[A] =
    Validated.invalidNel(msg)

}
