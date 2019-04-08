package controllers

import actions._
import com.gu.memsub._
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.subsv2.services.SubscriptionService
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.salesforce.SimpleContactRepository
import com.typesafe.scalalogging.LazyLogging
import models.ExistingPaymentOption
import org.joda.time.LocalDate
import org.joda.time.LocalDate.now
import play.api.libs.json.Json
import play.api.mvc.{BaseController, ControllerComponents}
import scalaz.std.option._
import scalaz.std.scalaFuture._
import scalaz.syntax.monad._
import scalaz.syntax.std.option._
import scalaz.syntax.traverse._
import scalaz.syntax.monadPlus._
import scalaz.{-\/, OptionT, \/, \/-}
import _root_.services.{AuthenticationService, IdentityAuthService}
import com.gu.i18n.Currency
import com.gu.zuora.rest.ZuoraRestService.ObjectAccount
import utils.{ListEither, OptionEither}

import scala.concurrent.{ExecutionContext, Future}

class ExistingPaymentOptionsController(commonActions: CommonActions, override val controllerComponents: ControllerComponents) extends BaseController with LazyLogging {
  import commonActions._
  implicit val executionContext: ExecutionContext = controllerComponents.executionContext
  lazy val authenticationService: AuthenticationService = IdentityAuthService

  def allSubscriptionsSince(
    date: LocalDate
  )(
    contactRepo: SimpleContactRepository,
    subService: SubscriptionService[Future]
  )(
    maybeUserId: Option[String]
  ): OptionT[OptionEither.FutureEither,  List[(AccountId, List[Subscription[SubscriptionPlan.AnyPlan]])]] = for {
    user <- OptionEither.liftFutureEither(maybeUserId)
    contact <- OptionEither(contactRepo.get(user))
    subscriptions <- OptionEither.liftEitherOption(subService.since[SubscriptionPlan.AnyPlan](date)(contact))
  } yield subscriptions.groupBy(_.accountId).toList

  def consolidatePaymentMethod(existingPaymentOptions: List[ExistingPaymentOption]): Iterable[ExistingPaymentOption] = {

    def extractConsolidationPart(existingPaymentOption: ExistingPaymentOption): Option[String] = existingPaymentOption.paymentMethodOption match {
      case Some(card: PaymentCard) => card.paymentCardDetails.map(_.lastFourDigits)
      case Some(dd: GoCardless) => Some(dd.accountNumber)
      case Some(payPal: PayPalMethod) => Some(payPal.email)
      case _ => None
    }

    def mapConsolidatedBackToSingle(consolidated: List[ExistingPaymentOption]): ExistingPaymentOption = {
      val theChosenOne = consolidated.head //TODO in future perhaps use custom fields on PaymentMethod to see which is safe to clone
      ExistingPaymentOption(
        freshlySignedIn = theChosenOne.freshlySignedIn,
        objectAccount = theChosenOne.objectAccount,
        paymentMethodOption = theChosenOne.paymentMethodOption,
        subscriptions = consolidated.flatMap(_.subscriptions)
      )
    }

    existingPaymentOptions.groupBy(extractConsolidationPart).filterKeys(_.isDefined).values.map(mapConsolidatedBackToSingle)
  }


  def cardThatWontBeExpiredOnFirstTransaction(cardDetails: PaymentCardDetails) =
    new LocalDate(cardDetails.expiryYear, cardDetails.expiryMonth, 1).isAfter(now.plusMonths(1))

  def existingPaymentOptions(currencyFilter: Option[String]) = BackendFromCookieAction.async { implicit request =>
    implicit val tp = request.touchpoint
    val maybeUserId = authenticationService.userId

    val eligibilityDate = now.minusMonths(3)

    val defaultMandateIdIfApplicable = "CLEARED"

    def paymentMethodStillValid(paymentMethodOption: Option[PaymentMethod]) = paymentMethodOption match {
      case Some(card: PaymentCard) => card.isReferenceTransaction && card.paymentCardDetails.exists(cardThatWontBeExpiredOnFirstTransaction)
      case Some(dd: GoCardless) => dd.mandateId != defaultMandateIdIfApplicable //i.e. mandateId a real reference and hasn't been cleared in Zuora because of mandate failure
      case _ => false
    }

    def paymentMethodHasNoFailures(paymentMethodOption: Option[PaymentMethod]) =
      !paymentMethodOption.flatMap(_.numConsecutiveFailures).exists(_ > 0)

    def paymentMethodIsActive(paymentMethodOption: Option[PaymentMethod]) =
      !paymentMethodOption.flatMap(_.paymentMethodStatus).contains("Closed")

    def currencyMatchesFilter(accountCurrency: Option[Currency]) =
      (accountCurrency.map(_.iso), currencyFilter) match {
        case (Some(accountCurrencyISO), Some(currencyFilterValue)) => accountCurrencyISO == currencyFilterValue
        case (None, Some(_)) => false // if the account has no currency but there is filter the account is not eligible
        case _ => true
      }

    logger.info(s"Attempting to retrieve existing payment options for identity user: ${maybeUserId.mkString}")
    (for {
      isFreshlySignedIn <- ListEither.liftList(tp.idapiService.RedirectAdvice.getRedirectAdvice(request.headers.get("Cookie").getOrElse("")).map(advice => \/-(advice.redirect.isEmpty)).recover { case x => \/.left(s"error getting idapi redirect for identity user $maybeUserId Reason: $x") })
      groupedSubsList <- ListEither.fromOptionEither(allSubscriptionsSince(eligibilityDate)(tp.contactRepo, tp.subService)(maybeUserId))
      (accountId, subscriptions) = groupedSubsList
      objectAccount <- ListEither.liftList(tp.zuoraRestService.getObjectAccount(accountId).recover { case x => \/.left(s"error receiving OBJECT account with account id $accountId. Reason: $x") })
      if currencyMatchesFilter(objectAccount.currency) &&
         objectAccount.defaultPaymentMethodId.isDefined
      paymentMethodOption <- ListEither.liftList(tp.paymentService.getPaymentMethod(accountId, Some(defaultMandateIdIfApplicable)).map(\/.right).recover { case x => \/.left(s"error retrieving payment method for account: $accountId. Reason: $x") })
      if paymentMethodStillValid(paymentMethodOption) &&
         paymentMethodHasNoFailures(paymentMethodOption) &&
         paymentMethodIsActive(paymentMethodOption)
    } yield ExistingPaymentOption(isFreshlySignedIn, objectAccount, paymentMethodOption, subscriptions)).run.run.map {
      case \/-(existingPaymentOptions) =>
        logger.info(s"Successfully retrieved eligible existing payment options for identity user: ${maybeUserId.mkString}")
        Ok(Json.toJson(consolidatePaymentMethod(existingPaymentOptions).map(_.toJson)))
      case -\/(message) =>
        logger.warn(s"Unable to retrieve eligible existing payment options for identity user ${maybeUserId.mkString} due to $message")
        InternalServerError("Failed to retrieve eligible existing payment options due to an internal error")
    }
  }

}
