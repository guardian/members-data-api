package services

import acceptance.data.Randoms.randomId
import acceptance.data.TestContact
import com.gu.i18n.Currency.GBP
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
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
      val paymentDetailsForSubscription = new PaymentDetailsForSubscription(mock[PaymentService])

      paymentDetailsForSubscription
        .getPaymentDetails(ContactAndSubscription(contact, digipackGift, true))
        .map(details =>
          details mustEqual PaymentDetails(
            pendingCancellation = false,
            chargedThroughDate = None,
            startDate = digipackGift.startDate,
            customerAcceptanceDate = digipackGift.startDate,
            nextPaymentPrice = None,
            lastPaymentDate = None,
            nextPaymentDate = None,
            termEndDate = digipackGift.termEndDate,
            pendingAmendment = false,
            paymentMethod = None,
            plan = PersonalPlan(
              name = "Digital Pack",
              price = Price(0f, GBP),
              interval = BillingPeriod.Year.noun,
            ),
            subscriberId = "AS-123123",
            remainingTrialLength = 0,
          ),
        )
    }

    "recognise a gifter's gift subscription" in {
      val contact = TestContact(randomId("identityId"))
      val paymentService = mock[PaymentService]
      val paymentDetailsForSubscription = new PaymentDetailsForSubscription(paymentService)
      val expectedPaymentDetails = PaymentDetails(digipackGift, None, None, None)

      paymentService.paymentDetails(
        any[Subscription[SubscriptionPlan.Free] \/ Subscription[SubscriptionPlan.Paid]],
        any[Option[String]],
      ) returns Future.successful(expectedPaymentDetails)

      paymentDetailsForSubscription
        .getPaymentDetails(ContactAndSubscription(contact, digipack, false))
        .map(details => details mustEqual expectedPaymentDetails)
    }

    "recognise a regular digital subscription" in {
      val contact = TestContact(randomId("identityId"))
      val paymentService = mock[PaymentService]
      val paymentDetailsForSubscription = new PaymentDetailsForSubscription(paymentService)
      val expectedPaymentDetails = PaymentDetails(digipack, None, None, None)

      paymentService.paymentDetails(
        any[Subscription[SubscriptionPlan.Free] \/ Subscription[SubscriptionPlan.Paid]](),
        any[Option[String]](),
      ) returns Future.successful(expectedPaymentDetails)

      paymentDetailsForSubscription
        .getPaymentDetails(ContactAndSubscription(contact, digipack, false))
        .map(details => details mustEqual expectedPaymentDetails)
    }

    "recognise a free subscription" in {
      val contact = TestContact(randomId("identityId"))
      val paymentService = mock[PaymentService]
      val paymentDetailsForSubscription = new PaymentDetailsForSubscription(paymentService)
      val expectedPaymentDetails = PaymentDetails(staff)

      paymentDetailsForSubscription
        .getPaymentDetails(ContactAndSubscription(contact, staff, false))
        .map(details => details mustEqual expectedPaymentDetails)
    }
  }
}
