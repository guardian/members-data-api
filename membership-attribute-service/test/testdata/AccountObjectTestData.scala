package testdata

import com.gu.i18n.Country
import com.gu.i18n.Currency.GBP
import com.gu.zuora.api.StripeUKMembershipGateway
import com.gu.memsub.Subscription.AccountId
import com.gu.zuora.rest.ZuoraRestService.{AccountObject, AccountSummary, BillToContact, DefaultPaymentMethod, PaymentMethodId, SoldToContact}


object AccountObjectTestData {
  private val testAccountId = AccountId("accountId")
  private val testPaymentMethodId = PaymentMethodId("testme")
  private val testIdentityId = "123"
  private val currency = GBP
  val accountObjectWithBalance = AccountObject(testAccountId, 20.0, Some(currency), Some(testPaymentMethodId), Some(StripeUKMembershipGateway))
  val accountObjectWithZeroBalance = AccountObject(testAccountId, 0, Some(currency), Some(testPaymentMethodId))
}

object AccountSummaryTestData {
  private val testAccountId = AccountId("accountId")
  private val testPaymentMethodId = PaymentMethodId("testme")
  private val testIdentityId = "123"

  def accountSummaryWith(balance: Double, paymentMethodId: PaymentMethodId, accountId: AccountId) =
    AccountSummary(
      id = accountId,
      identityId = Some(testIdentityId),
      billToContact = BillToContact(Some("email"), Some(Country.UK)),
      soldToContact = SoldToContact(
        title = None,
        firstName = Some("Joe"),
        lastName = "Bloggs",
        None, None, None, None, None, None
      ),
      currency = None,
      balance = balance,
      defaultPaymentMethod = Some(DefaultPaymentMethod(paymentMethodId))
    )

  val accountSummaryWithBalance = accountSummaryWith(20.0, testPaymentMethodId, testAccountId)
  val accountSummaryWithZeroBalance = accountSummaryWith(0, testPaymentMethodId, testAccountId)
}