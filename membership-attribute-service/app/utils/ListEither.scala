package utils

import scalaz.{EitherT, IList, ListT, OptionT, \/}
import scalaz.std.scalaFuture._

import scala.concurrent.{ExecutionContext, Future}

object ListEither {

  type FutureEither[X] = EitherT[Future, String, X]

  private def apply[A](m: Future[\/[String, IList[A]]]): ListT[FutureEither, A] =
    ListT[FutureEither, A](EitherT[Future, String, IList[A]](m))

  def fromOptionEither[A](value: OptionT[FutureEither, List[A]])(implicit ex: ExecutionContext): ListT[FutureEither, A] =
    ListT[FutureEither, A](value.map(IList.fromList).run.map(x => IList.fromOption(x).flatten))

  private def liftListDisjunction[A](x: Future[\/[String, A]])(implicit ex: ExecutionContext): ListT[FutureEither, A] =
    apply(x.map(_.map[IList[A]](a => IList(a))))

  def liftList[A](x: Future[Either[String, A]])(implicit ex: ExecutionContext): ListT[FutureEither, A] =
    liftListDisjunction(x.map(\/.fromEither))

  def liftEitherList[A](future: Future[A])(implicit ex: ExecutionContext): ListT[FutureEither, A] = {
    apply(future map { value: A =>
      \/.right[String, IList[A]](IList(value))
    })
  }

  def liftFutureEither[A](x: List[A]): ListT[FutureEither, A] =
    apply(Future.successful(\/.right[String, IList[A]](IList.fromList(x))))

  def liftFutureList[A](future: Future[List[A]])(implicit ex: ExecutionContext): ListT[FutureEither, A] =
    apply(future map { value: List[A] =>
      \/.right[String, IList[A]](IList.fromList(value))
    })

}
