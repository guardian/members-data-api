package services

import java.util.Locale

import com.gu.zuora.rest.ZuoraRestService.{Invoice, InvoiceId, PaymentMethodId, PaymentMethodResponse}
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalDate}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import testdata.AccountObjectTestData._
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
        val result: Future[Option[String]] = PaymentFailureAlerter.alertText(accountSummaryWithZeroBalance, membership, paymentMethodResponseNoFailures)

        result must be_==(None).await
      }

      "return none if one of the zuora calls returns a left" in {
        val result: Future[Option[String]] = PaymentFailureAlerter.alertText(accountSummaryWithBalance, membership, paymentMethodLeftResponse)

        result must be_==(None).await
      }

      "return a message for a member who is in payment failure" in {
        val result: Future[Option[String]] = PaymentFailureAlerter.alertText(accountSummaryWithBalance, membership, paymentMethodResponseRecentFailure)

        val attemptDateTime = DateTime.now().minusDays(1)
        val formatter = DateTimeFormat.forPattern("d MMMM yyyy").withLocale(Locale.ENGLISH)
        val expectedActionText = s"Our attempt to take payment for your Supporter membership failed on ${attemptDateTime.toString(formatter)}. Please check that the card details shown are up to date."

        result must be_==(Some(expectedActionText)).await
      }

      "return a message for a contributor who is in payment failure" in {
        val result: Future[Option[String]] = PaymentFailureAlerter.alertText(accountSummaryWithBalance, contributor, paymentMethodResponseRecentFailure)

        val attemptDateTime = DateTime.now().minusDays(1)
        val formatter = DateTimeFormat.forPattern("d MMMM yyyy").withLocale(Locale.ENGLISH)
        val expectedActionText = s"Our attempt to take payment for your contribution failed on ${attemptDateTime.toString(formatter)}. Please check that the card details shown are up to date."

        result must be_==(Some(expectedActionText)).await
      }

    }

    "alertAvailableFor" should {

      "return false for a member with a zero balance" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountObjectWithZeroBalance, membership, paymentMethodResponseNoFailures)

        result must be_==(false).await
      }

      "return false for a member with last invoice more than 27 days ago" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountObjectWithBalanceAndOldInvoice, membership, paymentMethodResponseStaleFailure)

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

      "return true for a member with a failed payment and an invoice in the last 27 days" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountObjectWithBalance, membership, paymentMethodResponseRecentFailure)

        result must be_==(true).await
      }

      "return true for a contributor with a failed payment and an invoice in the last 27 days" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountObjectWithBalance, contributor, paymentMethodResponseRecentFailure)

        result must be_==(true).await
      }

      "return false for a cancelled membership with a failed payment in the last 27 days" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountObjectWithBalance, cancelledMembership, paymentMethodResponseRecentFailure)

        result must be_==(false).await
      }

      "return false for an active membership with a failed payment in the last 27 days but no invoice in the last 27 days" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountObjectWithBalanceAndOldInvoice, membership, paymentMethodResponseRecentFailure)

        result must be_==(false).await
      }

    }
    "lastUnpaidInvoiceDate" should {
      "return None if there's one invoice and it has no balance" in {
        val freshNoBalanceInvoice = Invoice(
          InvoiceId("someId"),
          DateTime.now().minusDays(14),
          DateTime.now().minusDays(7),
          balance = 0,
          status = "Posted"
        )

        val lastUnpaidInvoiceDate = PaymentFailureAlerter.latestUnpaidInvoiceDate(invoices = List(freshNoBalanceInvoice))

        lastUnpaidInvoiceDate.isEmpty
      }

      "return the invoice date if there is one invoice and it has a balance" in {
        val invoiceDate = DateTime.now().minusDays(14).withTimeAtStartOfDay()

        val freshInvoiceWithABalance = Invoice(
          InvoiceId("someId"),
          invoiceDate,
          DateTime.now().minusDays(7),
          balance = 12.34,
          status = "Posted"
        )
        val lastUnpaidInvoiceDate = PaymentFailureAlerter.latestUnpaidInvoiceDate(invoices = List(freshInvoiceWithABalance))

        lastUnpaidInvoiceDate === Some(invoiceDate)
      }

      "return the latest invoice date if there are two, both with a balance" in {
        val invoiceDateLatest = DateTime.now().minusDays(14).withTimeAtStartOfDay()
        val invoiceDateOlder = DateTime.now().minusDays(21).withTimeAtStartOfDay()

        val latestInvoiceWithABalance = Invoice(
          InvoiceId("someId"),
          invoiceDateLatest,
          DateTime.now().minusDays(7),
          balance = 12.34,
          status = "Posted"
        )

        val oldInvoiceWithABalance = Invoice(
          InvoiceId("someId2"),
          invoiceDateOlder,
          DateTime.now().minusDays(14),
          balance = 12.34,
          status = "Posted"
        )
        val lastUnpaidInvoiceDate = PaymentFailureAlerter.latestUnpaidInvoiceDate(invoices = List(latestInvoiceWithABalance, oldInvoiceWithABalance))

        lastUnpaidInvoiceDate === Some(invoiceDateLatest)
      }
    }

    "return the latest unpaid invoice date if there is a more recent paid invoice too" in {
      val invoiceDateLatest = DateTime.now().minusDays(14).withTimeAtStartOfDay()
      val invoiceDateOlder = DateTime.now().minusDays(21).withTimeAtStartOfDay()

      val latestInvoiceWithNoBalance = Invoice(
        InvoiceId("someId"),
        invoiceDateLatest,
        DateTime.now().minusDays(7),
        balance = 0,
        status = "Posted"
      )

      val oldInvoiceWithABalance = Invoice(
        InvoiceId("someId2"),
        invoiceDateOlder,
        DateTime.now().minusDays(14),
        balance = 12.34,
        status = "Posted"
      )
      val lastUnpaidInvoiceDate = PaymentFailureAlerter.latestUnpaidInvoiceDate(invoices = List(latestInvoiceWithNoBalance, oldInvoiceWithABalance))

      lastUnpaidInvoiceDate === Some(invoiceDateOlder)
    }

    "ignore an invoice that isn't posted" in {
      val invoiceDate = DateTime.now().minusDays(14).withTimeAtStartOfDay()

      val freshDraftInvoiceWithABalance = Invoice(
        InvoiceId("someId"),
        invoiceDate,
        DateTime.now().minusDays(7),
        balance = 12.34,
        status = "Draft"
      )
      val lastUnpaidInvoiceDate = PaymentFailureAlerter.latestUnpaidInvoiceDate(invoices = List(freshDraftInvoiceWithABalance))

      lastUnpaidInvoiceDate === None
    }
  }
}
