package com.gu.memsub.subsv2.reads

import org.joda.time.LocalDate
import play.api.libs.json._
import scalaz.std.list._
import scalaz.syntax.std.list._
import scalaz.syntax.traverse._
import scalaz.{Applicative, NonEmptyList}

object CommonReads {

  val dateFormat = "yyyy-MM-dd"
  implicit val localReads: Reads[LocalDate] = JodaReads.jodaLocalDateReads(dateFormat)
  implicit val localWrites: Writes[LocalDate] = JodaWrites.jodaLocalDateWrites(dateFormat)

  /** play provides its own applicative instance in play.api.libs.functional.syntax where you use "and" instead of |@| but that is FAR TOO READABLE TO
    * BE TRUSTED. more seriously you need an applicative instance to convert a List[JsResult[A]] to a JsResult[List[A]] and so we might as well be
    * consistent and use it over play's attempts
    */
  implicit object JsResultApplicative extends Applicative[JsResult] {
    override def point[A](a: => A): JsResult[A] = JsSuccess(a)

    override def ap[A, B](fa: => JsResult[A])(f: => JsResult[(A) => B]): JsResult[B] = (fa, f) match {
      case (JsSuccess(a, _), JsSuccess(func, _)) => JsSuccess(func(a))
      case (err1 @ JsError(_), err2 @ JsError(_)) => err1 ++ err2
      case (err @ JsError(_), _) => err
      case (_, err @ JsError(_)) => err
    }
  }

  // this reader reads a list but only throws an error if everything failed
  def niceListReads[A: Reads]: Reads[List[A]] = new Reads[List[A]] {
    override def reads(json: JsValue): JsResult[List[A]] = json match {
      case JsArray(items) =>
        items.map(_.validate[A]).partition(_.isSuccess) match {
          case (successes, errors) if successes.nonEmpty || errors.isEmpty =>
            (successes.toList: List[JsResult[A]]).sequence[JsResult, A]
          case (successes, errors) if successes.isEmpty => JsError(errors.mkString)
        }
      case _ => JsError(s"Failed to read $json as a list")
    }
  }

  def nelReads[A](implicit r: Reads[List[A]]): Reads[NonEmptyList[A]] =
    (json: JsValue) => {
      val jsResult = r.reads(json)
      val errors = jsResult.asEither.left.toOption.mkString
      jsResult.flatMap(_.toNel.toOption.fold[JsResult[NonEmptyList[A]]](JsError(s"List was empty - $errors"))(JsSuccess(_)))
    }
}
