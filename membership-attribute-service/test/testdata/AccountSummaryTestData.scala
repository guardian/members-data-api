package testdata

import com.gu.memsub.Subscription.AccountId
import com.gu.zuora.rest.ZuoraRestService.{AccountSummary, BillToContact, DefaultPaymentMethod, PaymentMethodId, SoldToContact}

object AccountSummaryTestData {
  val testAccountId = AccountId("accountId")
  val testPaymentMethodId = PaymentMethodId("testme")
  val testIdentityId = "123"

  def accountSummaryWith(balance: Double, paymentMethodId: PaymentMethodId, accountId: AccountId) =
    AccountSummary(
      id = accountId,
      identityId = Some(testIdentityId),
      billToContact = BillToContact(Some("email"), None),
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