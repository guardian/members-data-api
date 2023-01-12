package utils

import scalaz.{OptionT, \/}
import utils.SimpleEitherT.SimpleEitherT

import scala.concurrent.{ExecutionContext, Future}

// this is helping us stack future/either/option
object OptionEither {
  type OptionTEither[A] = OptionT[SimpleEitherT, A]

  def apply[A](m: Future[\/[String, Option[A]]]): OptionTEither[A] =
    OptionT[SimpleEitherT, A](SimpleEitherT(m))

  private def liftOptionDisjunction[A](x: Future[\/[String, A]])(implicit ex: ExecutionContext): OptionTEither[A] =
    apply(x.map(_.map[Option[A]](Some.apply)))

  def liftOption[A](x: Future[Either[String, A]])(implicit ex: ExecutionContext): OptionTEither[A] =
    liftOptionDisjunction(x.map(\/.fromEither))

  def some[A](value: A)(implicit ex: ExecutionContext): OptionTEither[A] =
    apply(SimpleEitherT.right(Option(value)).run)

  def liftFutureEither[A](x: Option[A]): OptionTEither[A] =
    apply(Future.successful(\/.right[String, Option[A]](x)))

  def liftEitherOption[A](future: Future[A])(implicit ex: ExecutionContext): OptionTEither[A] = {
    apply(future map { value: A =>
      \/.right[String, Option[A]](Some(value))
    })
  }

}
