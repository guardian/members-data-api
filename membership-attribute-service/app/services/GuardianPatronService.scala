package services

import _root_.services.stripe.BasicStripeService
import com.github.nscala_time.time.Imports.DateTimeFormat
import com.gu.memsub.Subscription._
import com.gu.memsub._
import com.gu.memsub.subsv2.ReaderType.Direct
import com.gu.memsub.subsv2.{Subscription, _}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.PersonalPlan
import com.gu.stripe.Stripe
import models.{AccountDetails, DynamoSupporterRatePlanItem}
import monitoring.CreateMetrics
import scalaz.std.scalaFuture._
import scalaz.{EitherT, NonEmptyList}
import utils.SimpleEitherT.SimpleEitherT

import scala.concurrent.{ExecutionContext, Future}

class GuardianPatronService(
    supporterProductDataService: SupporterProductDataService,
    patronsStripeService: BasicStripeService,
    stripePatronsPublicKey: String,
    createMetrics: CreateMetrics,
)(implicit executionContext: ExecutionContext) {
  private val metrics = createMetrics.forService(classOf[GuardianPatronService])

  def getGuardianPatronAccountDetails(userId: String)(implicit logPrefix: LogPrefix): SimpleEitherT[List[AccountDetails]] = {
    metrics.measureDurationEither("getGuardianPatronAccountDetails") {
      for {
        supporterRatePlanItems <- supporterProductDataService.getSupporterRatePlanItems(userId)
        stripeDetails <- EitherT.rightT(getListDetailsFromStripe(supporterRatePlanItems))
      } yield stripeDetails
    }
  }

  private def getListDetailsFromStripe(items: List[DynamoSupporterRatePlanItem])(implicit logPrefix: LogPrefix): Future[List[AccountDetails]] = {
    Future.sequence(
      items
        .filter(isGuardianPatronProduct)
        .map(_.subscriptionName)
        .map(fetchAccountDetailsFromStripe),
    )
  }

  private def isGuardianPatronProduct(item: DynamoSupporterRatePlanItem) =
    item.productRatePlanId == Catalog.guardianPatronProductRatePlanId.get

  private def fetchAccountDetailsFromStripe(subscriptionId: String)(implicit logPrefix: LogPrefix): Future[AccountDetails] =
    metrics.measureDuration("fetchAccountDetailsFromStripe") {
      for {
        subscription <- patronsStripeService.fetchSubscription(subscriptionId)
        paymentDetails <- patronsStripeService.fetchPaymentMethod(subscription.customer.id)
      } yield accountDetailsFromStripeSubscription(subscription, paymentDetails, stripePatronsPublicKey)
    }

  private def billingPeriodFromInterval(interval: String): ZBillingPeriod = interval match {
    case "year" => ZYear
    case _ => ZMonth
  }

  private def accountDetailsFromStripeSubscription(
      subscription: Stripe.Subscription,
      paymentDetails: Stripe.CustomersPaymentMethods,
      stripePublicKey: String,
  ): AccountDetails = {
    val price = Price(subscription.plan.amount.toFloat, subscription.plan.currency)
    AccountDetails(
      contactId = "Guardian Patrons don't have a Salesforce contactId",
      regNumber = None,
      email = Some(subscription.customer.email),
      deliveryAddress = None,
      subscription = Subscription(
        id = Id(subscription.id),
        subscriptionNumber = SubscriptionNumber(subscription.id),
        accountId = AccountId(subscription.customer.id),
        contractEffectiveDate = subscription.created,
        customerAcceptanceDate = subscription.created,
        termEndDate = subscription.currentPeriodEnd,
        isCancelled = subscription.isCancelled,
        ratePlans = List(
          RatePlan(
            id = RatePlanId("guardian_patron_unused"), // only used for contribution amount change
            productRatePlanId = Catalog.guardianPatronProductRatePlanId,
            productName = "guardianpatron",
            lastChangeType = None,
            features = Nil,
            ratePlanCharges = NonEmptyList(
              RatePlanCharge(
                id = SubscriptionRatePlanChargeId(""), // only used for update contribution amount
                productRatePlanChargeId = ProductRatePlanChargeId(""), // benefit is only used for paper days (was Benefit.GuardianPatron)
                pricing = PricingSummary(Map(subscription.plan.currency -> price)),
                zBillingPeriod = Some(billingPeriodFromInterval(subscription.plan.interval)),
                specificBillingPeriod = None, // only used for fixed period e.g. GW 6 for 6
                endDateCondition = SubscriptionEnd,
                upToPeriods = None, // only used for fixed periods
                upToPeriodsType = None, // only used for fixed periods
                chargedThroughDate = Some(subscription.currentPeriodEnd),
                effectiveStartDate = subscription.currentPeriodStart,
                effectiveEndDate = subscription.currentPeriodEnd,
              ),
            ),
          ),
        ),
        readerType = Direct,
        autoRenew = true,
      ),
      paymentDetails = PaymentDetails(
        subscriberId = subscription.id,
        startDate = subscription.currentPeriodStart,
        customerAcceptanceDate = subscription.created,
        chargedThroughDate = Some(subscription.currentPeriodEnd),
        termEndDate = subscription.currentPeriodEnd,
        nextPaymentPrice = Some(subscription.plan.amount),
        lastPaymentDate = Some(subscription.currentPeriodStart),
        nextPaymentDate = subscription.nextPaymentDate,
        nextInvoiceDate = subscription.nextPaymentDate,
        remainingTrialLength = 0,
        pendingCancellation = subscription.isPastDue,
        paymentMethod = paymentDetails.cardStripeList.data.headOption.map(card =>
          PaymentCard(
            isReferenceTransaction = false,
            cardType = Some(card.`type`),
            paymentCardDetails = Some(PaymentCardDetails(card.last4, card.exp_month, card.exp_year)),
          ),
        ),
        plan = PersonalPlan("guardianpatron", price, subscription.plan.interval),
      ),
      billingCountry = None,
      stripePublicKey = stripePublicKey,
      accountHasMissedRecentPayments = subscription.isPastDue,
      safeToUpdatePaymentMethod = false, // TODO, this will require quite a few changes so for now we won't allow it
      isAutoRenew = true,
      alertText = None,
      accountId = subscription.customer.id,
      cancellationEffectiveDate = subscription.cancellationEffectiveDate.map(_.toString(DateTimeFormat.forPattern("yyyy-MM-dd"))),
    )
  }
}
