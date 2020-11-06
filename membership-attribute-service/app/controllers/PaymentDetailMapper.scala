package controllers

import com.gu.memsub.{BillingPeriod, Price}
import com.gu.memsub.services.PaymentService
import com.gu.memsub.subsv2.ReaderType.Gift
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.PersonalPlan
import scalaz.{-\/, \/, \/-}

import scala.concurrent.Future

object PaymentDetailMapper {

  def getGiftPaymentDetails(giftSub: Subscription[SubscriptionPlan.Paid]) = PaymentDetails(
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
    maybeUserId: Option[String],
    freeOrPaidSub: Subscription[SubscriptionPlan.Free] \/ Subscription[SubscriptionPlan.Paid],
    paymentService: PaymentService
  ): Future[PaymentDetails] = freeOrPaidSub match {
    case \/-(giftSub) if giftSub.gifteeIdentityId == maybeUserId && giftSub.readerType == Gift =>
      Future.successful(getGiftPaymentDetails(giftSub))
    case \/-(paidSub)  =>
      paymentService.paymentDetails(freeOrPaidSub, defaultMandateIdIfApplicable = Some(""))
    case -\/(freeSub) => Future.successful(PaymentDetails(freeSub))
  }

}
