package controllers

import actions._
import com.gu.memsub.{GoCardless, PaymentCard, PaymentCardDetails, PaymentMethod}
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.subsv2.services.SubscriptionService
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.salesforce.SimpleContactRepository
import com.typesafe.scalalogging.LazyLogging
import models.ExistingPaymentOption
import org.joda.time.{LocalDate}
import org.joda.time.LocalDate.now
import play.api.libs.json.{Json}
import play.api.mvc.{BaseController, ControllerComponents}
import scalaz.std.option._
import scalaz.std.scalaFuture._
import scalaz.syntax.monad._
import scalaz.syntax.std.option._
import scalaz.syntax.traverse._
import scalaz.syntax.monadPlus._
import scalaz.{-\/, OptionT, \/, \/-}
import services.{AuthenticationService, IdentityAuthService}
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

  def cardThatWontBeExpiredOnFirstTransaction(cardDetails: PaymentCardDetails) =
    new LocalDate(cardDetails.expiryYear, cardDetails.expiryMonth, 1).isAfter(now.plusMonths(1))

  def existingPaymentOptions(cardEnabled: Boolean, directDebitEnabled: Boolean, currencyFilter: String) = BackendFromCookieAction.async { implicit request =>
    implicit val tp = request.touchpoint
    val maybeUserId = authenticationService.userId

    val eligibilityDate = now.minusMonths(3)

    val defaultMandateIdIfApplicable = "CLEARED"

    def isPaymentMethodMatchingFilters(paymentMethodOption: Option[PaymentMethod]) = paymentMethodOption match {
      case Some(_: PaymentCard) => cardEnabled
      case Some(_: GoCardless) => directDebitEnabled
      case _ => false
    }

    def isPaymentMethodStillValid(paymentMethodOption: Option[PaymentMethod]) = paymentMethodOption match {
      case Some(card: PaymentCard) => card.paymentCardDetails.exists(cardThatWontBeExpiredOnFirstTransaction)
      case Some(dd: GoCardless) => dd.mandateId != defaultMandateIdIfApplicable //i.e. mandateId a real reference and hasn't been cleared in Zuora because of mandate failure
      case _ => false
    }

    logger.info(s"Attempting to retrieve existing payment options for identity user: ${maybeUserId.mkString}")
    (for {
      isFreshlySignedIn <- ListEither.liftList(tp.idapiService.RedirectAdvice.redirectUrl(request.headers.get("Cookie").getOrElse("")).map(urlOption => \/-(urlOption.isEmpty)).recover { case x => \/.left(s"error getting idapi redirect for identity user $maybeUserId Reason: $x") })
      groupedSubsList <- ListEither.fromOptionEither(allSubscriptionsSince(eligibilityDate)(tp.contactRepo, tp.subService)(maybeUserId))
      (accountId, subscriptions) = groupedSubsList
      accountSummary <- ListEither.liftList(tp.zuoraRestService.getAccount(accountId).recover { case x => \/.left(s"error receiving account summary for with account id $accountId. Reason: $x") })
      if accountSummary.currency.map(_.iso).contains(currencyFilter) &&
         accountSummary.defaultPaymentMethod.isDefined
      paymentMethodOption <- ListEither.liftList(tp.paymentService.getPaymentMethod(accountId, Some(defaultMandateIdIfApplicable)).map(\/.right).recover { case x => \/.left(s"error retrieving payment method for account: $accountId. Reason: $x") })
      if isPaymentMethodMatchingFilters(paymentMethodOption) &&
         isPaymentMethodStillValid(paymentMethodOption)
    } yield ExistingPaymentOption(isFreshlySignedIn, accountSummary, paymentMethodOption, subscriptions).toJson).run.run.map {
      case \/-(jsonList) =>
        logger.info(s"Successfully retrieved eligible existing payment options for identity user: ${maybeUserId.mkString}")
        Ok(Json.toJson(jsonList))
      case -\/(message) =>
        logger.warn(s"Unable to retrieve eligible existing payment options for identity user ${maybeUserId.mkString} due to $message")
        InternalServerError("Failed to retrieve eligible existing payment options due to an internal error")
    }
  }

}
