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
import components.TouchpointComponents
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
import scalaz.std.option._
import scalaz.std.scalaFuture._
import scalaz.syntax.monad._
import scalaz.syntax.std.option._
import scalaz.syntax.traverse._
import scalaz.{-\/, EitherT, OptionT, \/, \/-, _}

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
      stripeCardToken <- EitherT(Future.successful(updateForm.bindFromRequest().value \/> "no card token submitted with request"))
      sfUser <- EitherT(tp.contactRepo.get(user).map(_.flatMap(_ \/> s"no SF user $user")))
      subscription <- EitherT(tp.subService.current[P](sfUser).map(_.headOption).map (_ \/> s"no current subscriptions for the sfUser $sfUser"))
      account <- EitherT(tp.zuoraService.getAccount(subscription.accountId).map(\/.right).recover { case x => \/.left(s"error receiving account for subscription: ${subscription.name} with account id ${subscription.accountId}. Reason: $x") })
      stripeService <- EitherT(Future.successful(account.paymentGateway.flatMap(tp.stripeServicesByPaymentGateway.get) \/> s"No Stripe service available for account: ${account.id}"))
      updateResult <- EitherT(tp.paymentService.setPaymentCardWithStripeToken(subscription.accountId, stripeCardToken, stripeService, Some(user)).map(_ \/> "something missing when try to zuora payment card"))
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

  private def getUpToDatePaymentDetailsFromStripe(defaultPaymentMethodId: Option[String], paymentDetails: PaymentDetails)(implicit tp: TouchpointComponents): Future[PaymentDetails] = {
    paymentDetails.paymentMethod.map {
      case card: PaymentCard =>
        (for {
          paymentMethodId <- OptionT(Future.successful(defaultPaymentMethodId.map(_.trim).filter(_.nonEmpty)))
          zuoraPaymentMethod <- tp.zuoraService.getPaymentMethod(paymentMethodId).liftM[OptionT]
          customerId <- OptionT(Future.successful(zuoraPaymentMethod.secondTokenId.map(_.trim).filter(_.startsWith("cus_"))))
          maybeUkStripeCustomer <- tp.ukStripeService.Customer.read(customerId).map(Option(_)).recover { case _ => None }.liftM[OptionT]
          maybeStripeCustomer <-
            // Performance optimisation not to go to AU Stripe if UK is one gotten above
            if (maybeUkStripeCustomer.nonEmpty) Future.successful(maybeUkStripeCustomer).liftM[OptionT]
            else tp.auStripeService.Customer.read(customerId).map(Option(_)).recover { case _ => None }.liftM[OptionT]
        } yield {
          // TODO consider broadcasting to a queue somewhere iff the payment method in Zuora is out of date compared to Stripe
          card.copy(
            cardType = maybeStripeCustomer.map(_.card).map(_.`type`),
            paymentCardDetails = maybeStripeCustomer.map(_.card).map(card => PaymentCardDetails(card.last4, card.exp_month, card.exp_year))
          )
        }).run
      case x => Future.successful(Some(x))
    }.sequence.map { maybeUpdatedPaymentMethod =>
      paymentDetails.copy(paymentMethod = maybeUpdatedPaymentMethod.flatten orElse paymentDetails.paymentMethod)
    }
  }

  def paymentDetails[P <: SubscriptionPlan.Paid : SubPlanReads, F <: SubscriptionPlan.Free : SubPlanReads] = mmaAction.async { implicit request =>
    implicit val tp = request.touchpoint
    val maybeUserId = authenticationService.userId

    logger.info(s"Attempting to retrieve payment details for identity user: ${maybeUserId.mkString}")
    (for {
      user <- OptionEither.liftFutureEither(maybeUserId)
      contact <- OptionEither(tp.contactRepo.get(user))
      freeOrPaidSub <- OptionEither(tp.subService.either[F, P](contact).map(_.leftMap(message => s"couldn't read sub from zuora for crmId ${contact.salesforceAccountId} due to $message")))
      details <- OptionEither.liftOption(tp.paymentService.paymentDetails(freeOrPaidSub).map(\/.right))
      sub = freeOrPaidSub.fold(identity, identity)
      account <- OptionEither.liftOption(tp.zuoraService.getAccount(sub.accountId).map(\/.right).recover { case x => \/.left(s"error receiving account for subscription: ${sub.name} with account id ${sub.accountId}. Reason: $x") })
      publicKey = account.paymentGateway.flatMap(tp.stripeServicesByPaymentGateway.get).map(_.publicKey)
      paymentDetails <- OptionEither.liftOption(tp.paymentService.paymentDetails(freeOrPaidSub).map(\/.right).recover { case x => \/.left(s"error retrieving payment details for subscription: ${sub.name}. Reason: $x") })
      upToDatePaymentDetails <- OptionEither.liftOption(getUpToDatePaymentDetailsFromStripe(account.defaultPaymentMethodId, paymentDetails).map(\/.right).recover { case x => \/.left(s"error getting up-to-date details for payment method id: ${account.defaultPaymentMethodId.mkString}. Reason: $x") })
    } yield (contact, upToDatePaymentDetails, publicKey).toResult).run.run.map {
      case \/-(Some(result)) =>
        logger.info(s"Successfully retrieved payment details result for identity user: ${maybeUserId.mkString}")
        result
      case \/-(None) =>
        logger.info(s"identity user doesn't exist in SF: ${maybeUserId.mkString}")
        Ok(Json.obj())
      case -\/(message) =>
        logger.warn(s"Unable to retrieve payment details result for identity user ${maybeUserId.mkString} due to $message")
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
