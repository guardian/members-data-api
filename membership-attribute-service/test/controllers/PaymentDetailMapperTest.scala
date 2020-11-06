package controllers

import com.gu.memsub.services.PaymentService
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.services.model.PaymentDetails
import org.joda.time.LocalDate
import org.mockito.Mockito.when
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import scalaz.{-\/, \/, \/-}
import testdata.SubscriptionTestData

import scala.concurrent.Future

class PaymentDetailMapperTest(implicit ee: ExecutionEnv) extends Specification with SubscriptionTestData with Mockito {

  override def referenceDate = LocalDate.now()

  "PaymentDetailMapper" should {
    "recognise a giftee's gift subscription" in {
      val mockPaymentService = mock[PaymentService]
      PaymentDetailMapper.paymentDetailsForSub(
        Some("12345"),
        \/-(digipackGift),
        mockPaymentService
      ).map(
        details => details mustEqual PaymentDetailMapper.getGiftPaymentDetails(digipackGift)
      )
    }

    "recognise a gifter's gift subscription" in {
      val mockPaymentService = mock[PaymentService]
      val expectedPaymentDetails = PaymentDetails(digipackGift, None, None, None)

      when(mockPaymentService.paymentDetails(
        any[Subscription[SubscriptionPlan.Free] \/ Subscription[SubscriptionPlan.Paid]](),
        any[Option[String]]()
      )).thenReturn(
        Future.successful(expectedPaymentDetails)
      )

      PaymentDetailMapper.paymentDetailsForSub(
        Some("6789"),
        \/-(digipack),
        mockPaymentService
      ).map(
        details => details mustEqual expectedPaymentDetails
      )
    }

    "recognise a regular digital subscription" in {
      val mockPaymentService = mock[PaymentService]
      val expectedPaymentDetails = PaymentDetails(digipack, None, None, None)

      when(mockPaymentService.paymentDetails(
        any[Subscription[SubscriptionPlan.Free] \/ Subscription[SubscriptionPlan.Paid]](),
        any[Option[String]]()
      )).thenReturn(
        Future.successful(expectedPaymentDetails)
      )

      PaymentDetailMapper.paymentDetailsForSub(
        Some("12345"),
        \/-(digipack),
        mockPaymentService
      ).map(
        details => details mustEqual expectedPaymentDetails
      )
    }

    "recognise a free subscription" in {
      val mockPaymentService = mock[PaymentService]
      val expectedPaymentDetails = PaymentDetails(friend)

      PaymentDetailMapper.paymentDetailsForSub(
        Some("12345"),
        -\/(friend),
        mockPaymentService
      ).map(
        details => details mustEqual expectedPaymentDetails
      )
    }

  }


}
