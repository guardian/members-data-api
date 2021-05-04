package utils

import scalaz.{EitherT, OptionT, \/}

import scala.concurrent.{ExecutionContext, Future}

// this is helping us stack future/either/option
object OptionEither {

  type FutureEither[X] = EitherT[String, Future, X]

  def apply[A](m: Future[\/[String, Option[A]]]): OptionT[FutureEither, A] =
    OptionT[FutureEither, A](EitherT[String, Future, Option[A]](m))

  def liftOption[A](x: Future[\/[String, A]])(implicit ex: ExecutionContext): OptionT[FutureEither, A] =
    apply(x.map(_.map[Option[A]](Some.apply)))

  def liftFutureEither[A](x: Option[A]): OptionT[FutureEither, A] =
    apply(Future.successful(\/.right[String,Option[A]](x)))

  def liftFutureOption[A](future: Future[Option[A]])(implicit ex: ExecutionContext): OptionT[FutureEither, A] =
    apply(future map { value: Option[A] =>
      \/.right[String, Option[A]](value)
    })

  def liftEitherOption[A](future: Future[A])(implicit ex: ExecutionContext): OptionT[FutureEither, A] = {
    apply(future map { value: A =>
      \/.right[String, Option[A]](Some(value))
    })
  }

}
