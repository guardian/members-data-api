package utils

import scalaz.{EitherT, \/}

import scala.concurrent.{ExecutionContext, Future}

object SimpleEitherT {
  type SimpleEitherT[A] = EitherT[String, Future, A]

  def apply[T](f: Future[\/[String, T]]): SimpleEitherT[T] = EitherT(f)

  def apply[T](f: Future[Either[String, T]])(implicit ec: ExecutionContext): SimpleEitherT[T] =
    SimpleEitherT(f.map(\/.fromEither))

  def rightT[T](x: Future[T])(implicit ec: ExecutionContext): SimpleEitherT[T] = {
    apply[T](x.map(\/.right[String, T]))
  }

  def right[T](x: T)(implicit ec: ExecutionContext): SimpleEitherT[T] =
    rightT(Future.successful(x))
}
