package services

import com.typesafe.scalalogging.LazyLogging
import models.PaymentDetails
import models.PaymentDetails.PersonalPlan
import models.subscription.subsv2.SubscriptionPlan.AnyPlan
import models.subscription.subsv2.{Subscription, SubscriptionPlan}
import models.subscription.{BillingPeriod, Price}
import scalaz.\/
import services.DifferentiateSubscription.differentiateSubscription
import services.payment.PaymentService

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class PaymentDetailsForSubscription(paymentService: PaymentService) extends LazyLogging {
  def apply(subscription: Subscription[AnyPlan], isGiftRedemption: Boolean)(implicit ec: ExecutionContext): Future[PaymentDetails] = {
    val differentiated = differentiateSubscription(subscription)
    differentiated match {
      case Right(giftSub) if isGiftRedemption =>
        Future.successful(giftPaymentDetailsFor(giftSub))
      case Right(paidSub) =>
        val paymentDetails = paymentService.paymentDetails(\/.fromEither(differentiated), defaultMandateIdIfApplicable = Some(""))
        paymentDetails.onComplete {
          case Failure(exception) => logger.error(s"Failed to get payment details for $paidSub: $exception")
          case Success(_) => logger.info(s"Successfully got payment details for $paidSub")
        }
        paymentDetails
      case Left(freeSub) => Future.successful(PaymentDetails(freeSub))
    }
  }

  private def giftPaymentDetailsFor(giftSubscription: Subscription[SubscriptionPlan.Paid]): PaymentDetails = PaymentDetails(
    pendingCancellation = giftSubscription.isCancelled,
    chargedThroughDate = None,
    startDate = giftSubscription.startDate,
    customerAcceptanceDate = giftSubscription.startDate,
    nextPaymentPrice = None,
    lastPaymentDate = None,
    nextPaymentDate = None,
    termEndDate = giftSubscription.termEndDate,
    pendingAmendment = false,
    paymentMethod = None,
    plan = PersonalPlan(
      name = giftSubscription.plan.productName,
      price = Price(0f, giftSubscription.plan.charges.currencies.head),
      interval = BillingPeriod.Year.noun,
    ),
    subscriberId = giftSubscription.name.get,
    remainingTrialLength = 0,
  )
}
