package controllers
import actions._
import play.api.libs.concurrent.Execution.Implicits._
import services.{AuthenticationService, IdentityAuthService}
import com.gu.memsub._
import com.gu.memsub.subsv2.SubscriptionPlan
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.services.model.PaymentDetails
import com.typesafe.scalalogging.LazyLogging
import configuration.Config
import json.PaymentCardUpdateResultWriters._
import models.AccountDetails._
import models.ApiErrors._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.Json
import play.api.mvc.Results._
import play.filters.cors.CORSActionBuilder

import scala.concurrent.Future
import scalaz.std.scalaFuture._
import scalaz.syntax.std.option._
import scalaz.{-\/, EitherT, OptionT, \/, \/-}

class AccountController extends LazyLogging {

  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val corsCardFilter = CORSActionBuilder(Config.mmaCardCorsConfig)
  lazy val mmaCorsFilter = CORSActionBuilder(Config.mmaCorsConfig)
  lazy val mmaAction = NoCacheAction andThen mmaCorsFilter andThen BackendFromCookieAction
  lazy val mmaCardAction = NoCacheAction andThen corsCardFilter andThen BackendFromCookieAction

  def updateCard[P <: SubscriptionPlan.AnyPlan : SubPlanReads] = mmaCardAction.async { implicit request =>

    // TODO - refactor to use the Zuora-only based lookup, like in AttributeController.pickAttributes - https://trello.com/c/RlESb8jG

    val updateForm = Form { single("stripeToken" -> nonEmptyText) }
    val tp = request.touchpoint
    val maybeUserId = authenticationService.userId
    logger.info(s"Attempting to update card for $maybeUserId")
    (for {
      user <- EitherT(Future.successful( maybeUserId \/> "no identity cookie for user"))
      sfUser <- EitherT(tp.contactRepo.get(user).map(_.flatMap(_ \/> s"no SF user $user")))
      subscription <- EitherT(tp.subService.current[P](sfUser).map(_.headOption).map (_ \/> s"no current subscriptions for the sfUser $sfUser"))
      account <- EitherT(tp.zuoraService.getAccount(subscription.accountId).map(\/.right).recover { case x => \/.left(s"error receiving account for subscription: ${subscription.name} with account id ${subscription.accountId}. Reason: $x") })
      stripeService = if (account.paymentGateway.contains(tp.auStripeService.paymentGateway)) tp.auStripeService else tp.ukStripeService
      stripeCardToken <- EitherT(Future.successful(updateForm.bindFromRequest().value \/> "no card token submitted with request"))
      updateResult <- EitherT(tp.paymentService.setPaymentCardWithStripeToken(subscription.accountId, stripeCardToken, stripeService).map(_ \/> "something missing when try to zuora payment card"))
    } yield updateResult match {
      case success: CardUpdateSuccess => {
        logger.info(s"Successfully updated card for identity user: $user")
        Ok(Json.toJson(success))
      }
      case failure: CardUpdateFailure => {
        logger.error(s"Failed to update card for identity user: $user due to $failure")
        Forbidden(Json.toJson(failure))
      }
    }).run.map {
      case -\/(message) =>
        logger.warn(s"Failed to update card for user $maybeUserId, due to $message")
        notFound
      case \/-(result) => result
    }
  }

  def paymentDetails[P <: SubscriptionPlan.Paid : SubPlanReads, F <: SubscriptionPlan.Free : SubPlanReads] = mmaAction.async { implicit request =>
    val tp = request.touchpoint
    val maybeUserId = authenticationService.userId
    val stripeServices = Seq(tp.ukStripeService, tp.auStripeService)

    logger.info(s"Attempting to retrieve payment details for identity user: $maybeUserId")
    (for {
      user <- OptionEither.liftFutureEither(maybeUserId)
      contact <- OptionEither(tp.contactRepo.get(user))
      freeOrPaidSub <- OptionEither(tp.subService.either[F, P](contact).map(_.leftMap(message => s"couldn't read sub from zuora for crmId ${contact.salesforceAccountId} due to $message")))
      details <- OptionEither.liftOption(tp.paymentService.paymentDetails(freeOrPaidSub).map[\/[String, PaymentDetails]](\/.right))
      sub = freeOrPaidSub.fold(identity, identity)
      account <- OptionEither.liftOption(tp.zuoraService.getAccount(sub.accountId).map(\/.right).recover { case x => \/.left(s"error receiving account for subscription: ${sub.name} with account id ${sub.accountId}. Reason: $x") })
      publicKey = account.paymentGateway.flatMap(paymentGateway => stripeServices.find(_.paymentGateway == paymentGateway)).map(_.publicKey)
    } yield (contact, details, publicKey).toResult).run.run.map {
      case \/-(Some(result)) =>
        logger.info(s"Successfully retrieved payment details result for identity user: $maybeUserId")
        result
      case \/-(None) =>
        logger.info(s"identity user doesn't exist in SF: $maybeUserId")
        Ok(Json.obj())
      case -\/(message) =>
        logger.warn(s"Unable to retrieve payment details result for identity user $maybeUserId due to $message")
        InternalServerError("Failed to retrieve payment details due to an internal error")
    }
  }

  def membershipUpdateCard = updateCard[SubscriptionPlan.PaidMember]
  def digitalPackUpdateCard = updateCard[SubscriptionPlan.Digipack]

  def membershipDetails = paymentDetails[SubscriptionPlan.PaidMember, SubscriptionPlan.FreeMember]
  def monthlyContributionDetails = paymentDetails[SubscriptionPlan.Contributor, Nothing]
  def digitalPackDetails = paymentDetails[SubscriptionPlan.Digipack, Nothing]
}

// this is helping us stack future/either/option
object OptionEither {

  type FutureEither[X] = EitherT[Future, String, X]

  def apply[A](m: Future[\/[String, Option[A]]]): OptionT[FutureEither, A] =
    OptionT[FutureEither, A](EitherT[Future, String, Option[A]](m))

  def liftOption[A](x: Future[\/[String, A]]): OptionT[FutureEither, A] =
    apply(x.map(_.map[Option[A]](Some.apply)))

  def liftFutureEither[A](x: Option[A]): OptionT[FutureEither, A] =
    apply(Future.successful(\/.right[String,Option[A]](x)))

}
