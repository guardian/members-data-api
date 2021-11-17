package utils

import scalaz.{EitherT, ListT, OptionT, \/}
import scalaz.std.scalaFuture._
import scala.concurrent.{ExecutionContext, Future}

object ListEither {

  type FutureEither[X] = EitherT[String, Future, X]

  def apply[A](m: Future[\/[String, List[A]]]): ListT[FutureEither, A] =
    ListT[FutureEither, A](EitherT[String, Future, List[A]](m))

  def fromOptionEither[A](value: OptionT[FutureEither, List[A]])(implicit ex: ExecutionContext): ListT[FutureEither, A] =
    ListT[FutureEither, A](value.run.map(_.toList.flatten))

  def liftList[A](x: Future[\/[String, A]])(implicit ex: ExecutionContext): ListT[FutureEither, A] =
    apply(x.map(_.map[List[A]](a => List(a))))

  def liftEitherList[A](future: Future[A])(implicit ex: ExecutionContext): ListT[FutureEither, A] = {
    apply(future map { value: A =>
      \/.right[String, List[A]](List(value))
    })
  }

}
