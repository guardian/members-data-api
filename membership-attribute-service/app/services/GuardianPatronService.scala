package services

import com.github.nscala_time.time.Imports.DateTimeFormat
import com.gu.i18n.Currency.GBP
import com.gu.memsub.BillingPeriod.{Month, Year}
import com.gu.memsub.Product.GuardianPatron
import com.gu.memsub.Subscription._
import com.gu.memsub.subsv2.ReaderType.Direct
import com.gu.memsub.subsv2.{CovariantNonEmptyList, PaidCharge, PaidSubscriptionPlan, Subscription}
import com.gu.memsub.{Benefit, PaymentCard, PaymentCardDetails, Price, PricingSummary}
import com.gu.monitoring.SafeLogger
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.PersonalPlan
import com.gu.stripe.Stripe
import components.TouchpointComponents
import models.{AccountDetails, DynamoSupporterRatePlanItem}
import scalaz.std.option._
import scalaz.std.scalaFuture._
import scalaz.syntax.monad._
import scalaz.syntax.traverse._
import scalaz.{EitherT, IList, ListT, OptionT, \/}
import services.SupporterRatePlanToAttributesMapper.guardianPatronProductRatePlanId
import utils.OptionEither

import scala.concurrent.{ExecutionContext, Future}

object GuardianPatronService {
  private def billingPeriodFromInterval(interval: String) = interval match {
    case "year" => Year
    case _      => Month
  }
  private def accountDetailsFromStripeSubscription(
      subscription: Stripe.Subscription,
      paymentDetails: Stripe.CustomersPaymentMethods,
      stripePublicKey: String
  ) = {
    val price = Price(subscription.plan.amount.toFloat, subscription.plan.currency.getOrElse(GBP))
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
        hasPendingFreePlan = false,
        plans = CovariantNonEmptyList(
          PaidSubscriptionPlan(
            id = RatePlanId(guardianPatronProductRatePlanId),
            productRatePlanId = ProductRatePlanId(guardianPatronProductRatePlanId),
            name = subscription.plan.id,
            description = "Guardian Patron",
            productName = "Guardian Patron",
            product = GuardianPatron,
            features = Nil,
            charges = PaidCharge(
              benefit = Benefit.GuardianPatron,
              billingPeriod = billingPeriodFromInterval(subscription.plan.interval),
              price = PricingSummary(Map(subscription.plan.currency.getOrElse(GBP) -> price)),
              chargeId = ProductRatePlanChargeId(""),
              subRatePlanChargeId = SubscriptionRatePlanChargeId("")
            ),
            chargedThrough = Some(subscription.currentPeriodEnd),
            start = subscription.currentPeriodStart,
            end = subscription.currentPeriodEnd
          ),
          tail = Nil
        ),
        readerType = Direct,
        gifteeIdentityId = None,
        autoRenew = true
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
        remainingTrialLength = 0,
        pendingCancellation = subscription.isPastDue,
        pendingAmendment = false,
        paymentMethod = paymentDetails.cardStripeList.data.headOption.map(card =>
          PaymentCard(
            isReferenceTransaction = false,
            cardType = Some(card.`type`),
            paymentCardDetails = Some(PaymentCardDetails(card.last4, card.exp_month, card.exp_year))
          )
        ),
        plan = PersonalPlan("guardianpatron", price, subscription.plan.interval)
      ),
      billingCountry = None,
      stripePublicKey = stripePublicKey,
      accountHasMissedRecentPayments = subscription.isPastDue,
      safeToUpdatePaymentMethod = false, // TODO, this will require quite a few changes so for now we won't allow it
      isAutoRenew = true,
      alertText = None,
      accountId = subscription.customer.id,
      cancellationEffectiveDate = subscription.cancellationEffectiveDate.map(_.toString(DateTimeFormat.forPattern("yyyy-MM-dd")))
    )
  }

  private def getDetailsFromStripe(subscriptionId: String)(implicit tp: TouchpointComponents, executionContext: ExecutionContext) = for {
    subscription <- tp.patronsStripeService.Subscription.read(subscriptionId)
    _ = System.out.println(s"Found subscription $subscription")
    paymentDetails <- tp.patronsStripeService.PaymentMethod.read(subscription.customer.id)
    _ = System.out.println(s"Found payment details $paymentDetails")
  } yield accountDetailsFromStripeSubscription(subscription, paymentDetails, tp.tpConfig.stripePatrons.stripeCredentials.publicKey)

  private def getListDetailsFromStripe(
      items: List[DynamoSupporterRatePlanItem]
  )(implicit tp: TouchpointComponents, executionContext: ExecutionContext) =
    Future.sequence(
      items
        .filter(_.productRatePlanId == guardianPatronProductRatePlanId)
        .map(item => getDetailsFromStripe(item.subscriptionName))
    )

  def getGuardianPatronAccountDetails(maybeIdentityId: Option[String])(implicit tp: TouchpointComponents, executionContext: ExecutionContext) = {
    for {
      identityId <- OptionEither.liftFutureEither(maybeIdentityId)
      supporterRatePlanItems <- OptionEither.liftOption(tp.supporterProductDataService.getSupporterRatePlanItems(identityId).value)
      stripeDetails <- OptionEither.liftEitherOption(getListDetailsFromStripe(supporterRatePlanItems))
    } yield stripeDetails
  }
}
