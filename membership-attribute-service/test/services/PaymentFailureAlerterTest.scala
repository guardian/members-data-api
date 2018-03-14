package services

import java.util.Locale

import com.gu.zuora.rest.ZuoraRestService.{PaymentMethodId, PaymentMethodResponse}
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalDate}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import testdata.AccountObjectTestData.{accountObjectWithBalance, accountObjectWithZeroBalance}
import testdata.AccountSummaryTestData.{accountSummaryWithBalance, accountSummaryWithZeroBalance}
import testdata.SubscriptionTestData

import scala.concurrent.Future
import scalaz.\/


class PaymentFailureAlerterTest(implicit ee: ExecutionEnv)  extends Specification with SubscriptionTestData {
  override def referenceDate = new LocalDate()

  def paymentMethodResponseNoFailures(id: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(0, "CreditCardReferenceTransaction", referenceDate.toDateTimeAtCurrentTime)))
  def paymentMethodResponseRecentFailure(id: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(1, "CreditCardReferenceTransaction", DateTime.now().minusDays(1))))
  def paymentMethodLeftResponse(id: PaymentMethodId) = Future.successful(\/.left("Something's gone wrong!"))
  def paymentMethodResponseStaleFailure(id: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(1, "CreditCardReferenceTransaction", DateTime.now().minusMonths(2))))

  "PaymentFailureAlerterTest" should {
    "membershipAlertText" should {
      "not return any for a user with no balance" in {
        val result: Future[Option[String]] = PaymentFailureAlerter.membershipAlertText(accountSummaryWithZeroBalance, membership, paymentMethodResponseNoFailures)

        result must be_==(None).await
      }

      "return none if one of the zuora calls returns a left" in {
        val result: Future[Option[String]] = PaymentFailureAlerter.membershipAlertText(accountSummaryWithBalance, membership, paymentMethodLeftResponse)

        result must be_==(None).await
      }

      // We are currently never returning alertText on calls to /mma-membership
      //This test should be un-ignored once we resume returning the alertText, if relevant.
      "return a message for a member who is in payment failure" in {
        skipped {
          val result: Future[Option[String]] = PaymentFailureAlerter.membershipAlertText(accountSummaryWithBalance, membership, paymentMethodResponseRecentFailure)

          val attemptDateTime = DateTime.now().minusDays(1)
          val formatter = DateTimeFormat.forPattern("d MMMM yyyy").withLocale(Locale.ENGLISH)
          val expectedActionText = s"Our attempt to take payment for your Supporter membership failed on ${attemptDateTime.toString(formatter)}. Please check below that your card details are up to date."

          result must be_==(Some(expectedActionText)).await
        }
      }

      // Temporarily the expected behaviour - delete once we start returning alertText
      "TEMPORARY - still not return alertText even if a member is payment failure" in {
        val result: Future[Option[String]] = PaymentFailureAlerter.membershipAlertText(accountSummaryWithBalance, membership, paymentMethodResponseRecentFailure)

        val attemptDateTime = DateTime.now().minusDays(1)
        val formatter = DateTimeFormat.forPattern("d MMMM yyyy").withLocale(Locale.ENGLISH)

        result must be_==(None).await
        }

    }

    "alertAvailableFor" should {

      "return false for a member with a zero balance" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountObjectWithZeroBalance, membership, paymentMethodResponseNoFailures)

        result must be_==(false).await
      }

      "return false for a member with a failed payment more than 27 days ago" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountObjectWithBalance, membership, paymentMethodResponseStaleFailure)

        result must be_==(false).await
      }

      "return false for a member with a balance but no failed payments" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountObjectWithBalance, membership, paymentMethodResponseNoFailures)

        result must be_==(false).await
      }

      "return false for a member who pays via paypal" in {
        def paymentMethodResponsePaypal(paymentMethodId: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(1, "PayPal", DateTime.now().minusDays(1))))

        val result = PaymentFailureAlerter.alertAvailableFor(accountObjectWithBalance, membership, paymentMethodResponsePaypal)

        result must be_==(false).await
      }

      "return true for for a member with a failed payment in the last 27 days" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountObjectWithBalance, membership, paymentMethodResponseRecentFailure)

        result must be_==(true).await
      }

      "return true for for a non-membership sub with a failed payment in the last 27 days too" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountObjectWithBalance, digipack, paymentMethodResponseRecentFailure)

        result must be_==(true).await
      }

    }

  }
}
