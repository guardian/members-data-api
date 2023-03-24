package utils

import scalaz.{OptionT, \/}
import utils.SimpleEitherT.SimpleEitherT

import scala.concurrent.{ExecutionContext, Future}

object OptionTEither {
  type OptionTEither[A] = OptionT[SimpleEitherT, A]

  def apply[A](m: Future[\/[String, Option[A]]]): OptionTEither[A] =
    OptionT[SimpleEitherT, A](SimpleEitherT(m))

  private def liftOptionDisjunction[A](x: Future[\/[String, A]])(implicit ex: ExecutionContext): OptionTEither[A] =
    apply(x.map(_.map[Option[A]](Some.apply)))

  def liftOption[A](x: Future[Either[String, A]])(implicit ex: ExecutionContext): OptionTEither[A] =
    liftOptionDisjunction(x.map(\/.fromEither))

  def some[A](value: A)(implicit ex: ExecutionContext): OptionTEither[A] =
    apply(SimpleEitherT.right(Option(value)).run)

  def fromOption[A](x: Option[A]): OptionTEither[A] =
    apply(Future.successful(\/.right[String, Option[A]](x)))

  def fromFutureOption[A](future: Future[Option[A]])(implicit ex: ExecutionContext): OptionTEither[A] = {
    apply(future.map(\/.right[String, Option[A]](_)))
  }
  def fromFuture[A](future: Future[A])(implicit ex: ExecutionContext): OptionTEither[A] =
    fromFutureOption(future.map(Some(_)))
}
