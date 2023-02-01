package testdata

import com.gu.i18n.Country
import com.gu.i18n.Currency.GBP
import com.gu.zuora.api.StripeUKMembershipGateway
import models.subscription.Subscription.{AccountId, AccountNumber}
import services.zuora.rest.ZuoraRestService._
import org.joda.time.DateTime

object AccountObjectTestData {
  private val testAccountId = AccountId("accountId")
  private val testPaymentMethodId = PaymentMethodId("testme")
  private val testIdentityId = "123"
  private val currency = GBP
  val accountObjectWithBalanceAndOldInvoice =
    AccountObject(testAccountId, 20.0, Some(currency), Some(testPaymentMethodId), Some(StripeUKMembershipGateway), Some(DateTime.now().minusDays(30)))
  val accountObjectWithBalance =
    AccountObject(testAccountId, 20.0, Some(currency), Some(testPaymentMethodId), Some(StripeUKMembershipGateway), Some(DateTime.now().minusDays(3)))
  val accountObjectWithZeroBalance = AccountObject(testAccountId, 0, Some(currency), Some(testPaymentMethodId), None, None)
}

object AccountSummaryTestData {
  private val testAccountId = AccountId("accountId")
  private val testPaymentMethodId = PaymentMethodId("testme")
  private val testIdentityId = "123"

  def accountSummaryWith(balance: Double, paymentMethodId: PaymentMethodId, accountId: AccountId) =
    AccountSummary(
      id = accountId,
      identityId = Some(testIdentityId),
      accountNumber = AccountNumber("accountNumber"),
      billToContact = BillToContact(email = Some("email"), country = Some(Country.UK)),
      soldToContact = SoldToContact(
        title = None,
        firstName = Some("Joe"),
        lastName = "Bloggs",
        email = None,
        address1 = None,
        address2 = None,
        city = None,
        postCode = None,
        state = None,
        country = None,
      ),
      invoices = List(
        Invoice(
          id = InvoiceId("someid"),
          invoiceNumber = "INV123",
          invoiceDate = DateTime.now().minusDays(14),
          dueDate = DateTime.now().minusDays(7),
          amount = 11.99,
          balance = balance,
          status = "Posted",
        ),
      ),
      payments = List(),
      currency = None,
      balance = balance,
      defaultPaymentMethod = Some(DefaultPaymentMethod(paymentMethodId)),
      sfContactId = SalesforceContactId("foo"),
    )

  val accountSummaryWithBalance = accountSummaryWith(20.0, testPaymentMethodId, testAccountId)
  val accountSummaryWithZeroBalance = accountSummaryWith(0, testPaymentMethodId, testAccountId)
}

object InvoiceAndPaymentTestData {

  val lessThanAWeekAgo = DateTime.now().minusDays(5)
  val moreThanAMonthAgo = DateTime.now().minusDays(32)
  val moreThanTwoMonthsAgo = DateTime.now().minusDays(62)
  val moreThanThreeMonthsAgo = DateTime.now().minusDays(92)

  val oldFreeInvoice = Invoice(
    id = InvoiceId("123"),
    invoiceNumber = "INV123",
    invoiceDate = DateTime.now().minusDays(32),
    dueDate = DateTime.now().minusDays(32),
    amount = 0,
    balance = 0,
    status = "Posted",
  )

  val oldUnpaidInvoice = Invoice(
    id = InvoiceId("123"),
    invoiceNumber = "INV123",
    invoiceDate = moreThanAMonthAgo,
    dueDate = moreThanAMonthAgo,
    amount = 11.99,
    balance = 11.99,
    status = "Posted",
  )

  val recentUnpaidInvoice = Invoice(
    id = InvoiceId("123"),
    invoiceNumber = "INV124",
    invoiceDate = lessThanAWeekAgo,
    dueDate = lessThanAWeekAgo,
    amount = 11.99,
    balance = 11.99,
    status = "Posted",
  )

  val paidInvoice = Invoice(
    id = InvoiceId("123"),
    invoiceNumber = "INV111",
    invoiceDate = moreThanAMonthAgo,
    dueDate = moreThanAMonthAgo,
    amount = 11.99,
    balance = 0,
    status = "Posted",
  )

  val failedPaymentForRecentUnpaidInvoice = Payment(status = "Failure", paidInvoices = List(PaidInvoice("INV111", 11.99)))
  val paymentForPaidInvoice = Payment(status = "Processed", paidInvoices = List(PaidInvoice("INV111", 11.99)))

}
