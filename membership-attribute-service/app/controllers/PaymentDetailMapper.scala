package controllers

import com.gu.memsub.services.PaymentService
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.memsub.{BillingPeriod, Price}
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.PersonalPlan
import scalaz.\/

import scala.concurrent.Future

object PaymentDetailMapper {

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
      interval = BillingPeriod.Year.noun
    ),
    subscriberId = giftSub.name.get,
    remainingTrialLength = 0
  )

  def paymentDetailsForSub(
    isGiftRedemption: Boolean,
    freeOrPaidSub: Either[Subscription[SubscriptionPlan.Free],Subscription[SubscriptionPlan.Paid]],
    paymentService: PaymentService
  ): Future[PaymentDetails] = freeOrPaidSub match {
    case Right(giftSub) if isGiftRedemption =>
      Future.successful(getGiftPaymentDetails(giftSub))
    case Right(paidSub)  =>
      paymentService.paymentDetails(\/.fromEither(freeOrPaidSub), defaultMandateIdIfApplicable = Some(""))
    case Left(freeSub) => Future.successful(PaymentDetails(freeSub))
  }

}
