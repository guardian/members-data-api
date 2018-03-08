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
  def paymentMethodLeftResponse(id: PaymentMethodId) = Future.successful(\/.left("Something's gone wrong!"))

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

      "return a message for a member who is in payment failure" in {
        val result: Future[Option[String]] = PaymentFailureAlerter.membershipAlertText(accountSummaryWithBalance, membership, paymentMethodResponseRecentFailure)

        val attemptDateTime = DateTime.now().minusDays(1)
        val formatter = DateTimeFormat.forPattern("d MMMM yyyy").withLocale(Locale.ENGLISH)
        val expectedActionText = s"Our attempt to take payment for your Supporter membership failed on ${attemptDateTime.toString(formatter)}. Please check below that your card details are up to date."

        result must be_==(Some(expectedActionText)).await
      }

    }

  }
}
