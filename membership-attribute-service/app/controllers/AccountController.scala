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
import com.gu.stripe.{Stripe, StripeService}
import com.gu.zuora.api.RegionalStripeGateways
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

    val updateForm = Form { tuple("stripeToken" -> nonEmptyText, "publicKey" -> text) }
    val tp = request.touchpoint
    val maybeUserId = authenticationService.userId
    logger.info(s"Attempting to update card for $maybeUserId")
    (for {
      user <- EitherT(Future.successful(maybeUserId \/> "no identity cookie for user"))
      stripeDetails <- EitherT(Future.successful(updateForm.bindFromRequest().value \/> "no card token and public key submitted with request"))
      (stripeCardToken, stripePublicKey) = stripeDetails
      sfUser <- EitherT(tp.contactRepo.get(user).map(_.flatMap(_ \/> s"no SF user $user")))
      subscription <- EitherT(tp.subService.current[P](sfUser).map(_.headOption).map (_ \/> s"no current subscriptions for the sfUser $sfUser"))
      stripeService <- EitherT(Future.successful(tp.stripeServicesByPublicKey.get(stripePublicKey)).map(_ \/> s"No Stripe service for public key: $stripePublicKey"))
      updateResult <- EitherT(tp.paymentService.setPaymentCardWithStripeToken(subscription.accountId, stripeCardToken, stripeService, maybeUserId).map(_ \/> "something missing when try to zuora payment card"))
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

  private def findStripeCustomer(customerId: String, likelyStripeService: StripeService)(implicit tp: TouchpointComponents): Future[Option[Stripe.Customer]] = {
    val alternativeStripeService = if (likelyStripeService == tp.ukStripeService) tp.auStripeService else tp.ukStripeService
    likelyStripeService.Customer.read(customerId).recoverWith {
      case _ => alternativeStripeService.Customer.read(customerId)
    } map(Option(_)) recover {
      case _ => None
    }
  }

  private def getUpToDatePaymentDetailsFromStripe(accountId: Subscription.AccountId, paymentDetails: PaymentDetails)(implicit tp: TouchpointComponents): Future[PaymentDetails] = {
    paymentDetails.paymentMethod.map {
      case card: PaymentCard =>
        (for {
          account <- tp.zuoraService.getAccount(accountId).liftM[OptionT]
          defaultPaymentMethodId <- OptionT(Future.successful(account.defaultPaymentMethodId.map(_.trim).filter(_.nonEmpty)))
          zuoraPaymentMethod <- tp.zuoraService.getPaymentMethod(defaultPaymentMethodId).liftM[OptionT]
          customerId <- OptionT(Future.successful(zuoraPaymentMethod.secondTokenId.map(_.trim).filter(_.startsWith("cus_"))))
          paymentGateway <- OptionT(Future.successful(account.paymentGateway))
          stripeService <- OptionT(Future.successful(tp.stripeServicesByPaymentGateway.get(paymentGateway)))
          stripeCustomer <- OptionT(findStripeCustomer(customerId, stripeService))
          stripeCard = stripeCustomer.card
        } yield {
          // TODO consider broadcasting to a queue somewhere iff the payment method in Zuora is out of date compared to Stripe
          card.copy(
            cardType = Some(stripeCard.`type`),
            paymentCardDetails = Some(PaymentCardDetails(stripeCard.last4, stripeCard.exp_month, stripeCard.exp_year))
          )
        }).run
      case x => Future.successful(None) // not updated
    }.sequence.map { maybeUpdatedPaymentCard =>
      paymentDetails.copy(paymentMethod = maybeUpdatedPaymentCard.flatten orElse paymentDetails.paymentMethod)
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
      accountSummary <- OptionEither.liftOption(tp.zuoraRestService.getAccount(sub.accountId))
      stripeService = accountSummary.billToContact.country.map(RegionalStripeGateways.getGatewayForCountry).flatMap(tp.stripeServicesByPaymentGateway.get).getOrElse(tp.ukStripeService)
      paymentDetails <- OptionEither.liftOption(tp.paymentService.paymentDetails(freeOrPaidSub).map(\/.right).recover { case x => \/.left(s"error retrieving payment details for subscription: ${sub.name}. Reason: $x") })
      upToDatePaymentDetails <- OptionEither.liftOption(getUpToDatePaymentDetailsFromStripe(accountSummary.id, paymentDetails).map(\/.right).recover { case x => \/.left(s"error getting up-to-date card details for payment method of account: ${accountSummary.id}. Reason: $x") })
    } yield (contact, upToDatePaymentDetails, stripeService.publicKey).toResult).run.run.map {
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
  def paperUpdateCard = updateCard[SubscriptionPlan.PaperPlan]
  def contributionUpdateCard = updateCard[SubscriptionPlan.Contributor]

  def membershipDetails = paymentDetails[SubscriptionPlan.PaidMember, SubscriptionPlan.FreeMember]
  def monthlyContributionDetails = paymentDetails[SubscriptionPlan.Contributor, Nothing]
  def digitalPackDetails = paymentDetails[SubscriptionPlan.Digipack, Nothing]
  def paperDetails = paymentDetails[SubscriptionPlan.PaperPlan, Nothing]
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
