package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.i18n.Currency
import com.gu.memsub.Subscription.{AccountId, AccountNumber}
import services.zuora.rest.ZuoraRestService.{
  AccountSummary,
  BillToContact,
  DefaultPaymentMethod,
  Invoice,
  Payment,
  SalesforceContactId,
  SoldToContact,
}

object TestAccountSummary {
  def apply(
      id: AccountId = AccountId(randomId("accountId")),
      accountNumber: AccountNumber = AccountNumber(randomId("accountNumber")),
      identityId: Option[String] = None,
      billToContact: BillToContact = BillToContact(None, None),
      soldToContact: SoldToContact = SoldToContact(None, None, "Smith", None, None, None, None, None, None, None),
      invoices: List[Invoice] = Nil,
      payments: List[Payment] = Nil,
      currency: Option[Currency] = None,
      balance: Double = 10,
      defaultPaymentMethod: Option[DefaultPaymentMethod] = None,
      sfContactId: SalesforceContactId = SalesforceContactId(randomId("salesforceContactId")),
  ): AccountSummary = AccountSummary(
    id,
    accountNumber,
    identityId,
    billToContact,
    soldToContact,
    invoices,
    payments,
    currency,
    balance,
    defaultPaymentMethod,
    sfContactId,
  )
}
