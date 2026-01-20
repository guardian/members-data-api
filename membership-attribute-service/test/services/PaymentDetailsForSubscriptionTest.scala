package services

import acceptance.data.Randoms.randomId
import acceptance.data.TestContact
import com.gu.i18n.Currency.GBP
import com.gu.memsub.subsv2.{Subscription, RatePlan}
import com.gu.memsub.{BillingPeriod, Price}
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.PersonalPlan
import models.ContactAndSubscription
import org.joda.time.LocalDate
import org.mockito.ArgumentMatchers.any
import org.mockito.IdiomaticMockito
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import scalaz.\/
import services.zuora.payment.PaymentService
import testdata.SubscriptionTestData

import scala.concurrent.Future
import testdata.TestLogPrefix.testLogPrefix

class PaymentDetailsForSubscriptionTest(implicit ee: ExecutionEnv) extends Specification with SubscriptionTestData with IdiomaticMockito {

  override def referenceDate = LocalDate.now()

  "PaymentDetailMapper" should {
    "recognise a giftee's gift subscription" in {
      val contact = TestContact(randomId("identityId"))
      val paymentDetailsForSubscription = new PaymentDetailsForSubscription(mock[PaymentService], _ => Future.successful(catalog))

      paymentDetailsForSubscription
        .getPaymentDetails(ContactAndSubscription(contact, digipackGift, true))
        .map(details =>
          details mustEqual PaymentDetails(
            pendingCancellation = false,
            chargedThroughDate = None,
            startDate = digipackGift.contractEffectiveDate,
            customerAcceptanceDate = digipackGift.contractEffectiveDate,
            nextPaymentPrice = None,
            lastPaymentDate = None,
            nextPaymentDate = None,
            nextInvoiceDate = None,
            termEndDate = digipackGift.termEndDate,
            paymentMethod = None,
            plan = PersonalPlan(
              name = "Digital Pack",
              price = Price(0f, GBP),
              interval = BillingPeriod.Year.noun,
            ),
            subscriberId = "AS-123123",
            remainingTrialLength = 0,
            nextChargeDate = None,
            nextChargeAmount = None,
            remainingDiscountDays = None,
          ),
        )
    }

    "recognise a gifter's gift subscription" in {
      val contact = TestContact(randomId("identityId"))
      val paymentService = mock[PaymentService]
      val paymentDetailsForSubscription = new PaymentDetailsForSubscription(paymentService, _ => Future.successful(catalog))
      val expectedPaymentDetails = PaymentDetails.fromSubAndPaymentData(digipackGift, None, None, None, None, catalog)

      paymentService.paymentDetails(
        any[Subscription],
        any[Option[String]],
        catalog,
      ) returns Future.successful(expectedPaymentDetails)

      paymentDetailsForSubscription
        .getPaymentDetails(ContactAndSubscription(contact, digipack, false))
        .map(details => details mustEqual expectedPaymentDetails)
    }

    "recognise a regular digital subscription" in {
      val contact = TestContact(randomId("identityId"))
      val paymentService = mock[PaymentService]
      val paymentDetailsForSubscription = new PaymentDetailsForSubscription(paymentService, _ => Future.successful(catalog))
      val expectedPaymentDetails = PaymentDetails.fromSubAndPaymentData(digipack, None, None, None, None, catalog)

      paymentService.paymentDetails(
        any[Subscription](),
        any[Option[String]](),
        catalog,
      ) returns Future.successful(expectedPaymentDetails)

      paymentDetailsForSubscription
        .getPaymentDetails(ContactAndSubscription(contact, digipack, false))
        .map(details => details mustEqual expectedPaymentDetails)
    }

  }
}
