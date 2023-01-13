package utils

import scalaz.std.scalaFuture._
import scalaz.{EitherT, IList, ListT, OptionT, \/}
import utils.SimpleEitherT.SimpleEitherT

import scala.concurrent.{ExecutionContext, Future}

object ListTEither {
  type ListTEither[A] = ListT[SimpleEitherT, A]

  def apply[A](m: Future[\/[String, IList[A]]]): ListT[SimpleEitherT, A] =
    ListT[SimpleEitherT, A](EitherT[String, Future, IList[A]](m))

  def apply[A](m: Future[\/[String, List[A]]])(implicit ex: ExecutionContext): ListTEither[A] =
    ListT[SimpleEitherT, A](EitherT[String, Future, List[A]](m).map(IList.fromList))

  def fromEitherT[A](eitherT: SimpleEitherT[List[A]])(implicit ex: ExecutionContext): ListTEither[A] =
    apply[A](eitherT.run)

  def fromOptionEither[A](value: OptionT[SimpleEitherT, List[A]])(implicit ex: ExecutionContext): ListTEither[A] =
    ListT[SimpleEitherT, A](value.map(IList.fromList).run.map(x => IList.fromOption(x).flatten))

  def fromFutureOption[A](value: Future[String \/ Option[A]])(implicit ex: ExecutionContext): ListTEither[A] =
    apply[A](SimpleEitherT(value).map(_.toList).run)

  def singleDisjunction[A](x: Future[\/[String, A]])(implicit ex: ExecutionContext): ListTEither[A] =
    apply(x.map(_.map[IList[A]](a => IList(a))))

  def single[A](x: Future[Either[String, A]])(implicit ex: ExecutionContext): ListTEither[A] =
    singleDisjunction(x.map(\/.fromEither))

  def single[A](e: SimpleEitherT[A])(implicit ex: ExecutionContext): ListTEither[A] =
    singleDisjunction(e.run)

  def singleRightT[A](future: Future[A])(implicit ex: ExecutionContext): ListTEither[A] =
    single(SimpleEitherT.rightT(future))

  def fromList[A](x: List[A]): ListTEither[A] =
    ListTEither(Future.successful(\/.right[String, IList[A]](IList.fromList(x))))

  def fromFutureList[A](future: Future[List[A]])(implicit ex: ExecutionContext): ListTEither[A] =
    ListTEither(future map { value: List[A] =>
      \/.right[String, IList[A]](IList.fromList(value))
    })

}
