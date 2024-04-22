package controllers

import actions.{CommonActions, ContinueRegardlessOfSignInRecency}
import com.gu.i18n.Currency
import com.gu.identity.SignedInRecently
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.memsub._
import com.gu.monitoring.SafeLogging
import components.TouchpointComponents
import models.AccessScope.completeReadSelf
import models.ExistingPaymentOption
import monitoring.CreateMetrics
import org.joda.time.LocalDate
import org.joda.time.LocalDate.now
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import scalaz.-\/
import scalaz.std.scalaFuture._
import scalaz.syntax.monadPlus._
import _root_.services.salesforce.ContactRepository
import _root_.services.subscription.SubscriptionService
import _root_.services.zuora.rest.ZuoraRestService.ObjectAccount
import utils.ListTEither
import utils.SimpleEitherT.SimpleEitherT

import scala.concurrent.ExecutionContext

class ExistingPaymentOptionsController(
    commonActions: CommonActions,
    override val controllerComponents: ControllerComponents,
    createMetrics: CreateMetrics,
) extends BaseController
    with SafeLogging {
  import commonActions._
  implicit val executionContext: ExecutionContext = controllerComponents.executionContext
  val metrics = createMetrics.forService(classOf[ExistingPaymentOptionsController])

  def allSubscriptionsSince(
      date: LocalDate,
      maybeUserId: Option[String],
      contactRepository: ContactRepository,
      subscriptionService: SubscriptionService,
  ): SimpleEitherT[Map[AccountId, List[Subscription[SubscriptionPlan.AnyPlan]]]] =
    (for {
      user <- ListTEither.fromOption(maybeUserId)
      contact <- ListTEither.fromFutureOption(contactRepository.get(user))
      subscription <- ListTEither.fromFutureList(subscriptionService.since[SubscriptionPlan.AnyPlan](date)(contact))
    } yield subscription).toList.map(_.groupBy(_.accountId))

  def consolidatePaymentMethod(existingPaymentOptions: List[ExistingPaymentOption]): Iterable[ExistingPaymentOption] = {

    def extractConsolidationPart(existingPaymentOption: ExistingPaymentOption): Option[String] = existingPaymentOption.paymentMethodOption match {
      case Some(card: PaymentCard) => card.paymentCardDetails.map(_.lastFourDigits)
      case Some(dd: GoCardless) => Some(dd.accountNumber)
      case Some(payPal: PayPalMethod) => Some(payPal.email)
      case _ => None
    }

    def mapConsolidatedBackToSingle(consolidated: List[ExistingPaymentOption]): ExistingPaymentOption = {
      val theChosenOne = consolidated.head // TODO in future perhaps use custom fields on PaymentMethod to see which is safe to clone
      ExistingPaymentOption(
        freshlySignedIn = theChosenOne.freshlySignedIn,
        objectAccount = theChosenOne.objectAccount,
        paymentMethodOption = theChosenOne.paymentMethodOption,
        subscriptions = consolidated.flatMap(_.subscriptions),
      )
    }

    existingPaymentOptions.groupBy(extractConsolidationPart).view.filterKeys(_.isDefined).values.map(mapConsolidatedBackToSingle)
  }

  // TODO should probably fetch upToDate details from Stripe to determine this (rather than relying on Zuora) - see getUpToDatePaymentDetailsFromStripe in AccountController
  def cardThatWontBeExpiredOnFirstTransaction(cardDetails: PaymentCardDetails): Boolean =
    new LocalDate(cardDetails.expiryYear, cardDetails.expiryMonth, 1).isAfter(now.plusMonths(1))

  def existingPaymentOptions(currencyFilter: Option[String]): Action[AnyContent] =
    AuthorizeForRecentLogin(ContinueRegardlessOfSignInRecency, requiredScopes = List(completeReadSelf)).async { implicit request =>
      metrics.measureDuration("GET /user-attributes/me/existing-payment-options") {
        implicit val tp: TouchpointComponents = request.touchpoint
        val maybeUserId = request.redirectAdvice.userId
        val isSignedInRecently = request.redirectAdvice.signInStatus == SignedInRecently

        val eligibilityDate = now.minusMonths(3)

        val defaultMandateIdIfApplicable = "CLEARED"

        def paymentMethodStillValid(paymentMethodOption: Option[PaymentMethod]) = paymentMethodOption match {
          case Some(card: PaymentCard) => card.isReferenceTransaction && card.paymentCardDetails.exists(cardThatWontBeExpiredOnFirstTransaction)
          case Some(dd: GoCardless) =>
            dd.mandateId != defaultMandateIdIfApplicable // i.e. mandateId a real reference and hasn't been cleared in Zuora because of mandate failure
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
          groupedSubsList <- ListTEither.fromEitherT(
            allSubscriptionsSince(eligibilityDate, maybeUserId, tp.contactRepository, tp.subscriptionService).map(_.toList),
          )
          (accountId, subscriptions) = groupedSubsList
          objectAccount <- ListTEither.singleDisjunction(tp.zuoraRestService.getObjectAccount(accountId).recover { case x =>
            -\/[String, ObjectAccount](s"error receiving OBJECT account with account id $accountId. Reason: $x")
          }) if currencyMatchesFilter(objectAccount.currency) &&
            objectAccount.defaultPaymentMethodId.isDefined
          paymentMethodOption <- ListTEither.single(
            tp.paymentService
              .getPaymentMethod(accountId, Some(defaultMandateIdIfApplicable))
              .map(Right(_))
              .recover { case x => Left(s"error retrieving payment method for account: $accountId. Reason: $x") },
          )
          if paymentMethodStillValid(paymentMethodOption) &&
            paymentMethodHasNoFailures(paymentMethodOption) &&
            paymentMethodIsActive(paymentMethodOption)
        } yield ExistingPaymentOption(isSignedInRecently, objectAccount, paymentMethodOption, subscriptions)).run.run.map(_.toEither).map {
          case Right(existingPaymentOptions) =>
            logger.info(s"Successfully retrieved eligible existing payment options for identity user: ${maybeUserId.mkString}")
            Ok(Json.toJson(consolidatePaymentMethod(existingPaymentOptions.toList).map(_.toJson)))
          case Left(message) =>
            logger.warn(s"Unable to retrieve eligible existing payment options for identity user ${maybeUserId.mkString} due to $message")
            InternalServerError("Failed to retrieve eligible existing payment options due to an internal error")
        }
      }
    }
}
