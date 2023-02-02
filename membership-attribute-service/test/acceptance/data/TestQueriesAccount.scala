package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.i18n.Currency
import services.zuora.soap.PaymentGateway
import services.zuora.soap.models.Queries

object TestQueriesAccount {
  def apply(
      id: String = randomId("accountId"),
      billToId: String = randomId("billToId"),
      soldToId: String = randomId("soldToToId"),
      billCycleDay: Int = 1,
      creditBalance: Float = 66,
      currency: Option[Currency] = None,
      defaultPaymentMethodId: Option[String] = None,
      sfContactId: Option[String] = None,
      paymentGateway: Option[PaymentGateway] = None,
  ): Queries.Account = Queries.Account(
    id,
    billToId,
    soldToId,
    billCycleDay,
    creditBalance,
    currency,
    defaultPaymentMethodId,
    sfContactId,
    paymentGateway,
  )
}
