package services

import com.gu.memsub.Subscription.AccountId
import services.zuora.rest.ZuoraRestService.{Invoice, InvoiceId, PaymentMethodId, PaymentMethodResponse}
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalDate}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import testdata.AccountObjectTestData._
import testdata.AccountSummaryTestData.{accountSummaryWithBalance, accountSummaryWithZeroBalance}
import testdata.{InvoiceAndPaymentTestData, SubscriptionTestData}

import java.util.Locale
import scala.concurrent.Future

class PaymentFailureAlerterTest(implicit ee: ExecutionEnv) extends Specification with SubscriptionTestData {
  override def referenceDate = new LocalDate()

  def paymentMethodResponseNoFailures(id: PaymentMethodId) =
    Future.successful(Right(PaymentMethodResponse(0, "CreditCardReferenceTransaction", referenceDate.toDateTimeAtCurrentTime)))
  def paymentMethodResponseRecentFailure(id: PaymentMethodId) =
    Future.successful(Right(PaymentMethodResponse(1, "CreditCardReferenceTransaction", DateTime.now().minusDays(1))))
  def paymentMethodLeftResponse(id: PaymentMethodId) = Future.successful(Left("Something's gone wrong!"))
  def paymentMethodResponseStaleFailure(id: PaymentMethodId) =
    Future.successful(Right(PaymentMethodResponse(1, "CreditCardReferenceTransaction", DateTime.now().minusMonths(2))))

  "PaymentFailureAlerterTest" should {
    "membershipAlertText" should {
      "not return any for a user with no balance" in {
        val result: Future[Option[String]] =
          PaymentFailureAlerter.alertText(accountSummaryWithZeroBalance, membership, paymentMethodResponseNoFailures)

        result must beNone.await
      }

      "return none if one of the zuora calls returns a left" in {
        val result: Future[Option[String]] = PaymentFailureAlerter.alertText(accountSummaryWithBalance, membership, paymentMethodLeftResponse)

        result must beNone.await
      }

      "return a message for a member who is in payment failure" in {
        val result: Future[Option[String]] =
          PaymentFailureAlerter.alertText(accountSummaryWithBalance, membership, paymentMethodResponseRecentFailure)

        val attemptDateTime = DateTime.now().minusDays(1)
        val formatter = DateTimeFormat.forPattern("d MMMM yyyy").withLocale(Locale.ENGLISH)
        val expectedActionText = s"Our attempt to take payment for your Supporter membership failed on ${attemptDateTime.toString(formatter)}."

        result must beSome(expectedActionText).await
      }

      "return a message for a contributor who is in payment failure" in {
        val result: Future[Option[String]] =
          PaymentFailureAlerter.alertText(accountSummaryWithBalance, contributor, paymentMethodResponseRecentFailure)

        val attemptDateTime = DateTime.now().minusDays(1)
        val formatter = DateTimeFormat.forPattern("d MMMM yyyy").withLocale(Locale.ENGLISH)
        val expectedActionText = s"Our attempt to take payment for your contribution failed on ${attemptDateTime.toString(formatter)}."

        result must beSome(expectedActionText).await
      }

      "return a message for a digipack holder who is in payment failure" in {
        val result: Future[Option[String]] = PaymentFailureAlerter.alertText(accountSummaryWithBalance, digipack, paymentMethodResponseRecentFailure)

        val attemptDateTime = DateTime.now().minusDays(1)
        val formatter = DateTimeFormat.forPattern("d MMMM yyyy").withLocale(Locale.ENGLISH)
        val expectedActionText = s"Our attempt to take payment for your Digital Pack failed on ${attemptDateTime.toString(formatter)}."

        result must beSome(expectedActionText).await
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
        def paymentMethodResponsePaypal(paymentMethodId: PaymentMethodId) =
          Future.successful(Right(PaymentMethodResponse(1, "PayPal", DateTime.now().minusDays(1))))

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

      "return true for a digipack holder with a failed payment and an invoice in the last 27 days" in {
        val result = PaymentFailureAlerter.alertAvailableFor(accountObjectWithBalance, digipack, paymentMethodResponseRecentFailure)

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
          "INV123",
          DateTime.now().minusDays(14),
          DateTime.now().minusDays(7),
          amount = 12.34,
          balance = 0,
          status = "Posted",
        )

        val lastUnpaidInvoiceDate = PaymentFailureAlerter.latestUnpaidInvoiceDate(invoices = List(freshNoBalanceInvoice))

        lastUnpaidInvoiceDate.isEmpty
      }

      "return the invoice date if there is one invoice and it has a balance" in {
        val invoiceDate = DateTime.now().minusDays(14).withTimeAtStartOfDay()

        val freshInvoiceWithABalance = Invoice(
          InvoiceId("someId"),
          "INV123",
          invoiceDate,
          DateTime.now().minusDays(7),
          amount = 12.34,
          balance = 12.34,
          status = "Posted",
        )
        val lastUnpaidInvoiceDate = PaymentFailureAlerter.latestUnpaidInvoiceDate(invoices = List(freshInvoiceWithABalance))

        lastUnpaidInvoiceDate === Some(invoiceDate)
      }

      "return the latest invoice date if there are two, both with a balance" in {
        val invoiceDateLatest = DateTime.now().minusDays(14).withTimeAtStartOfDay()
        val invoiceDateOlder = DateTime.now().minusDays(21).withTimeAtStartOfDay()

        val latestInvoiceWithABalance = Invoice(
          InvoiceId("someId"),
          "INV123",
          invoiceDateLatest,
          DateTime.now().minusDays(7),
          amount = 12.34,
          balance = 12.34,
          status = "Posted",
        )

        val oldInvoiceWithABalance = Invoice(
          InvoiceId("someId2"),
          "INV123",
          invoiceDateOlder,
          DateTime.now().minusDays(14),
          amount = 12.34,
          balance = 12.34,
          status = "Posted",
        )
        val lastUnpaidInvoiceDate = PaymentFailureAlerter.latestUnpaidInvoiceDate(invoices = List(latestInvoiceWithABalance, oldInvoiceWithABalance))

        lastUnpaidInvoiceDate === Some(invoiceDateLatest)
      }

      "return the latest unpaid invoice date if there is a more recent paid invoice too" in {
        val invoiceDateLatest = DateTime.now().minusDays(14).withTimeAtStartOfDay()
        val invoiceDateOlder = DateTime.now().minusDays(21).withTimeAtStartOfDay()

        val latestInvoiceWithNoBalance = Invoice(
          InvoiceId("someId"),
          "INV123",
          invoiceDateLatest,
          DateTime.now().minusDays(7),
          amount = 11.99,
          balance = 0,
          status = "Posted",
        )

        val oldInvoiceWithABalance = Invoice(
          InvoiceId("someId2"),
          "INV123",
          invoiceDateOlder,
          DateTime.now().minusDays(14),
          amount = 12.34,
          balance = 12.34,
          status = "Posted",
        )
        val lastUnpaidInvoiceDate = PaymentFailureAlerter.latestUnpaidInvoiceDate(invoices = List(latestInvoiceWithNoBalance, oldInvoiceWithABalance))

        lastUnpaidInvoiceDate === Some(invoiceDateOlder)
      }

      "ignore an invoice that isn't posted" in {
        val invoiceDate = DateTime.now().minusDays(14).withTimeAtStartOfDay()

        val freshDraftInvoiceWithABalance = Invoice(
          InvoiceId("someId"),
          "INV123",
          invoiceDate,
          DateTime.now().minusDays(7),
          amount = 12.34,
          balance = 12.34,
          status = "Draft",
        )
        val lastUnpaidInvoiceDate = PaymentFailureAlerter.latestUnpaidInvoiceDate(invoices = List(freshDraftInvoiceWithABalance))

        lastUnpaidInvoiceDate === None
      }
    }

    "mostRecentPayableInvoicesOlderThanOneMonth" should {

      import InvoiceAndPaymentTestData._

      "return an empty list if the user's invoices all have amount = 0" in {
        val result = PaymentFailureAlerter.mostRecentPayableInvoicesOlderThanOneMonth(
          List(
            oldFreeInvoice,
            oldFreeInvoice.copy(invoiceDate = moreThanTwoMonthsAgo, dueDate = moreThanTwoMonthsAgo),
            oldFreeInvoice.copy(invoiceDate = moreThanThreeMonthsAgo, dueDate = moreThanThreeMonthsAgo),
          ),
        )
        result === Nil
      }

      "return an empty list if the user only has a new invoice" in {
        val result = PaymentFailureAlerter.mostRecentPayableInvoicesOlderThanOneMonth(List(recentUnpaidInvoice))
        result === Nil
      }

      "return the most recent two invoices older than one month if they are present" in {

        val twoMonthOldInvoice = oldUnpaidInvoice.copy(invoiceDate = moreThanTwoMonthsAgo, dueDate = moreThanTwoMonthsAgo)
        val threeMonthOldInvoice = oldUnpaidInvoice.copy(invoiceDate = moreThanThreeMonthsAgo, dueDate = moreThanThreeMonthsAgo)

        val result = PaymentFailureAlerter.mostRecentPayableInvoicesOlderThanOneMonth(
          List(
            oldUnpaidInvoice,
            twoMonthOldInvoice,
            threeMonthOldInvoice,
          ),
        )
        result === List(oldUnpaidInvoice, twoMonthOldInvoice)
      }

    }

    "accountHasMissedPayments" should {

      import InvoiceAndPaymentTestData._

      val accountId = AccountId("id123")

      "return false if the user has 0 amount invoices and no payments (i.e. sub which should be paid but has 100% discount)" in {
        PaymentFailureAlerter.accountHasMissedPayments(accountId, List(oldFreeInvoice), List()) === false
      }

      "return false if the user has no payments or invoices for a paid sub (e.g. brand new sub)" in {
        PaymentFailureAlerter.accountHasMissedPayments(accountId, List(), List()) === false
      }

      "return false if the user has a single paid invoice" in {
        PaymentFailureAlerter.accountHasMissedPayments(accountId, List(paidInvoice), List(paymentForPaidInvoice)) === false
      }

      "return false if the user has a recent unpaid invoice (where invoice is unpaid due to no payment attempts)" in {
        PaymentFailureAlerter.accountHasMissedPayments(accountId, List(recentUnpaidInvoice), List()) === false
      }

      "return false if the user has a recent unpaid invoice (where invoice is unpaid due to payment failures)" in {
        PaymentFailureAlerter.accountHasMissedPayments(accountId, List(recentUnpaidInvoice), List(failedPaymentForRecentUnpaidInvoice)) === false
      }

      "return true if the user has never paid an invoice, despite having a single invoice which is more than 30 days old" in {
        PaymentFailureAlerter.accountHasMissedPayments(accountId, List(oldUnpaidInvoice), List()) === true
      }

      "return true if the user has never paid an invoice, despite having two old invoices" in {
        val result = PaymentFailureAlerter.accountHasMissedPayments(
          accountId,
          List(oldUnpaidInvoice, oldUnpaidInvoice.copy(invoiceDate = moreThanTwoMonthsAgo, dueDate = moreThanTwoMonthsAgo)),
          List(),
        )
        result === true
      }

      "return true if the user hasn't paid a recent invoice, even if they have paid in the past" in {
        PaymentFailureAlerter.accountHasMissedPayments(accountId, List(oldUnpaidInvoice, paidInvoice), List(paymentForPaidInvoice)) === true
      }

    }

    "safeToAllowPaymentUpdate" should {

      import InvoiceAndPaymentTestData._

      val accountId = AccountId("id123")

      "return false if the user has an outstanding balance from an invoice older than 31 days" in {
        PaymentFailureAlerter.safeToAllowPaymentUpdate(accountId, List(oldUnpaidInvoice)) === false
      }

      "return true if the user has failed to pay for an invoice within the last month (normal payment failure scenario)" in {
        PaymentFailureAlerter.safeToAllowPaymentUpdate(accountId, List(recentUnpaidInvoice)) === true
      }
    }

  }
}
