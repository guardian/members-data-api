package controllers

import com.gu.memsub.services.PaymentService
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.memsub.{BillingPeriod, Price}
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.PersonalPlan
import com.typesafe.scalalogging.LazyLogging
import scalaz.\/

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object PaymentDetailMapper extends LazyLogging {

  def getGiftPaymentDetails(giftSub: Subscription[SubscriptionPlan.Paid]): PaymentDetails = PaymentDetails(
    pendingCancellation = giftSub.isCancelled,
    chargedThroughDate = None,
    startDate = giftSub.startDate,
    customerAcceptanceDate = giftSub.startDate,
    nextPaymentPrice = None,
    lastPaymentDate = None,
    nextPaymentDate = None,
    termEndDate = giftSub.termEndDate,
    pendingAmendment = false,
    paymentMethod = None,
    plan = PersonalPlan(
      name = giftSub.plan.productName,
      price = Price(0f, giftSub.plan.charges.currencies.head),
      interval = BillingPeriod.Year.noun,
    ),
    subscriberId = giftSub.name.get,
    remainingTrialLength = 0,
  )

  def paymentDetailsForSub(
      isGiftRedemption: Boolean,
      freeOrPaidSub: Either[Subscription[SubscriptionPlan.Free], Subscription[SubscriptionPlan.Paid]],
      paymentService: PaymentService,
  )(implicit ec: ExecutionContext): Future[PaymentDetails] = freeOrPaidSub match {
    case Right(giftSub) if isGiftRedemption =>
      Future.successful(getGiftPaymentDetails(giftSub))
    case Right(paidSub) =>
      val paymentDetails = paymentService.paymentDetails(\/.fromEither(freeOrPaidSub), defaultMandateIdIfApplicable = Some(""))
      paymentDetails.onComplete {
        case Failure(exception) => logger.error(s"Failed to get payment details for $paidSub: $exception")
        case Success(_) => logger.info(s"Successfully got payment details for $paidSub")
      }
      paymentDetails
    case Left(freeSub) => Future.successful(PaymentDetails(freeSub))
  }

}
