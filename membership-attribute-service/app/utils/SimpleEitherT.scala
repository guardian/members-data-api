package utils

import scalaz.std.scalaFuture._
import scalaz.{EitherT, \/}
import utils.ListTEither.ListTEither

import scala.concurrent.{ExecutionContext, Future}

object SimpleEitherT {
  type SimpleEitherT[A] = EitherT[String, Future, A]

  def apply[T](f: Future[\/[String, T]]): SimpleEitherT[T] = EitherT(f)

  def apply[T](f: Future[Either[String, T]])(implicit ec: ExecutionContext): SimpleEitherT[T] =
    SimpleEitherT(f.map(\/.fromEither))

  def rightT[T](x: Future[T])(implicit ec: ExecutionContext): SimpleEitherT[T] = {
    SimpleEitherT[T](x.map(\/.right[String, T]))
  }

  def leftT[T](x: Future[String])(implicit ec: ExecutionContext): SimpleEitherT[T] = {
    SimpleEitherT[T](x.map(\/.left[String, T]))
  }

  def right[T](x: T)(implicit ec: ExecutionContext): SimpleEitherT[T] =
    rightT(Future.successful(x))

  def left[T](x: String)(implicit ec: ExecutionContext): SimpleEitherT[T] =
    leftT[T](Future.successful(x))

  def fromFutureOption[T](f: Future[\/[String, Option[T]]], errorMessage: String)(implicit ec: ExecutionContext): SimpleEitherT[T] =
    SimpleEitherT(f).flatMap {
      case Some(value) => right(value)
      case _ => left(errorMessage)
    }

  def fromEither[T](either: Either[String, T])(implicit ec: ExecutionContext): SimpleEitherT[T] =
    apply(Future.successful(either))

  def fromListT[T](value: ListTEither[T])(implicit ec: ExecutionContext): SimpleEitherT[List[T]] = SimpleEitherT(value.toList.run)
}
