package services

import java.util.Locale

import com.gu.zuora.rest.ZuoraRestService.{PaymentMethodId, PaymentMethodResponse}
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalDate}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import testdata.AccountSummaryTestData.{accountSummaryWithBalance, accountSummaryWithZeroBalance}
import testdata.SubscriptionTestData

import scala.concurrent.Future
import scalaz.\/


class PaymentFailureAlerterTest(implicit ee: ExecutionEnv)  extends Specification with SubscriptionTestData {
  override def referenceDate = new LocalDate()

  def paymentMethodResponseNoFailures(id: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(0, "CreditCardReferenceTransaction", referenceDate.toDateTimeAtCurrentTime)))
  def paymentMethodResponseRecentFailure(id: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(1, "CreditCardReferenceTransaction", DateTime.now().minusDays(1))))
  def paymentMethodResponseStaleFailure(id: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(1, "CreditCardReferenceTransaction", DateTime.now().minusMonths(2))))

  "PaymentFailureAlerterTest" should {
    "alertAvailableFor" should {
      "return false for a member with a zero balance" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountSummaryWithZeroBalance, membership, paymentMethodResponseNoFailures)

        result must be_==(false).await
      }

      "return false for a member with a failed payment more than 27 days ago" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountSummaryWithBalance, membership, paymentMethodResponseStaleFailure)

        result must be_==(false).await
      }

      "return false for a member with a balance but no failed payments" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountSummaryWithBalance, membership, paymentMethodResponseNoFailures)

        result must be_==(false).await
      }

      "return false for a member who pays via paypal" in {
        def paymentMethodResponsePaypal(paymentMethodId: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(1, "PayPal", DateTime.now().minusDays(1))))

        val result = PaymentFailureAlerter.alertAvailableFor(accountSummaryWithBalance, membership, paymentMethodResponsePaypal)

        result must be_==(false).await
      }

      "return true for for a member with a failed payment in the last 27 days" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountSummaryWithBalance, membership, paymentMethodResponseRecentFailure)

        result must be_==(true).await
      }

      "return true for for a non-membership sub with a failed payment in the last 27 days too" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountSummaryWithBalance, digipack, paymentMethodResponseRecentFailure)

        result must be_==(true).await
      }

    }
    "membershipAlertText" should {
      "not return any text usually" in {
        val result: Future[Option[String]] = PaymentFailureAlerter.membershipAlertText(accountSummaryWithZeroBalance, membership, paymentMethodResponseNoFailures)

        result must be_==(None).await
      }

      "return none if one of the zuora calls returns a left" in {

      }

      "return a message for a member who is in payment failure" in {
        val result: Future[Option[String]] = PaymentFailureAlerter.membershipAlertText(accountSummaryWithBalance, membership, paymentMethodResponseRecentFailure)

        // DateTime.now().minusDays(1)
        val attemptDateTime = DateTime.now().minusDays(1)

        val formatter = DateTimeFormat.forPattern("dd MMMM yyyy").withLocale(Locale.ENGLISH)
        val whatami = attemptDateTime.toString(formatter)

        val expectedActionText = s"Our attempt to take payment for your membership failed on ${attemptDateTime.toString(formatter)}. Please check below that your card details are up to date."

        result must be_==(Some(expectedActionText)).await
      }

    }

  }
}
