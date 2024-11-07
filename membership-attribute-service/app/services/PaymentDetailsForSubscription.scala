package services

import com.gu.memsub.promo.LogImplicit.LoggableFuture
import com.gu.memsub.subsv2.{Catalog, Subscription}
import com.gu.memsub.{BillingPeriod, Price}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.PersonalPlan
import models.ContactAndSubscription
import services.zuora.payment.PaymentService

import scala.concurrent.{ExecutionContext, Future}

class PaymentDetailsForSubscription(paymentService: PaymentService, futureCatalog: Future[Catalog]) extends SafeLogging {

  def getPaymentDetails(
      contactAndSubscription: ContactAndSubscription,
  )(implicit ec: ExecutionContext, logPrefix: LogPrefix): Future[PaymentDetails] = {
    val subscription = contactAndSubscription.subscription
    for {
      catalog <- futureCatalog
      paymentDetails <-
        if (contactAndSubscription.isGiftRedemption)
          Future.successful(giftPaymentDetailsFor(subscription, catalog))
        else
          paymentService
            .paymentDetails(subscription, defaultMandateIdIfApplicable = Some(""), catalog)
            .withLogging(s"get payment details for $subscription")
    } yield paymentDetails
  }

  private def giftPaymentDetailsFor(giftSubscription: Subscription, catalog: Catalog): PaymentDetails = PaymentDetails(
    pendingCancellation = giftSubscription.isCancelled,
    chargedThroughDate = None,
    startDate = giftSubscription.contractEffectiveDate,
    customerAcceptanceDate = giftSubscription.contractEffectiveDate,
    nextPaymentPrice = None,
    lastPaymentDate = None,
    nextPaymentDate = None,
    nextInvoiceDate = None,
    termEndDate = giftSubscription.termEndDate,
    paymentMethod = None,
    plan = PersonalPlan(
      name = giftSubscription.plan(catalog).productName,
      price = Price(0f, giftSubscription.plan(catalog).chargesPrice.currencies.head),
      interval = BillingPeriod.Year.noun,
    ),
    subscriberId = giftSubscription.subscriptionNumber.getNumber,
    remainingTrialLength = 0,
  )
}
