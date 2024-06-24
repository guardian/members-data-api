package services

import _root_.services.SupporterRatePlanToAttributesMapper.guardianPatronProductRatePlanId
import _root_.services.stripe.BasicStripeService
import com.github.nscala_time.time.Imports.DateTimeFormat
import com.gu.memsub.BillingPeriod.{Month, RecurringPeriod, Year}
import com.gu.memsub.Product.GuardianPatron
import com.gu.memsub.Subscription._
import com.gu.memsub._
import com.gu.memsub.subsv2.ReaderType.Direct
import com.gu.memsub.subsv2.{CovariantNonEmptyList, RatePlanCharge, Subscription, RatePlan}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.PersonalPlan
import com.gu.stripe.Stripe
import models.{AccountDetails, DynamoSupporterRatePlanItem}
import monitoring.CreateMetrics
import scalaz.EitherT
import scalaz.std.scalaFuture._
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
    item.productRatePlanId == guardianPatronProductRatePlanId

  private def fetchAccountDetailsFromStripe(subscriptionId: String)(implicit logPrefix: LogPrefix): Future[AccountDetails] =
    metrics.measureDuration("fetchAccountDetailsFromStripe") {
      for {
        subscription <- patronsStripeService.fetchSubscription(subscriptionId)
        paymentDetails <- patronsStripeService.fetchPaymentMethod(subscription.customer.id)
      } yield accountDetailsFromStripeSubscription(subscription, paymentDetails, stripePatronsPublicKey)
    }

  private def billingPeriodFromInterval(interval: String): RecurringPeriod = interval match {
    case "year" => Year
    case _ => Month
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
        name = Name(subscription.id),
        accountId = AccountId(subscription.customer.id),
        startDate = subscription.created,
        acceptanceDate = subscription.created,
        termStartDate = subscription.currentPeriodStart,
        termEndDate = subscription.currentPeriodEnd,
        casActivationDate = None,
        promoCode = None,
        isCancelled = subscription.isCancelled,
        plans = CovariantNonEmptyList(
          RatePlan(
            id = RatePlanId(guardianPatronProductRatePlanId),
            productRatePlanId = ProductRatePlanId(guardianPatronProductRatePlanId),
            name = subscription.plan.id,
            description = "Guardian Patron",
            productName = "Guardian Patron",
            lastChangeType = None,
            productType = "Membership",
            product = GuardianPatron,
            features = Nil,
            charges = RatePlanCharge(
              benefit = Benefit.GuardianPatron,
              billingPeriod = billingPeriodFromInterval(subscription.plan.interval),
              price = PricingSummary(Map(subscription.plan.currency -> price)),
              chargeId = ProductRatePlanChargeId(""),
              subRatePlanChargeId = SubscriptionRatePlanChargeId(""),
            ),
            chargedThrough = Some(subscription.currentPeriodEnd),
            start = subscription.currentPeriodStart,
            end = subscription.currentPeriodEnd,
          ),
          tail = Nil,
        ),
        readerType = Direct,
        gifteeIdentityId = None,
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
