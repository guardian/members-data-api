package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.i18n.Currency
import services.zuora.soap.models.PaymentSummary
import services.zuora.soap.models.Queries.InvoiceItem
import org.joda.time.LocalDate

object TestPaymentSummary {
  def apply(current: InvoiceItem = TestInvoiceItem(), previous: Seq[InvoiceItem] = Nil, currency: Currency = Currency.GBP): PaymentSummary =
    PaymentSummary(
      current,
      previous,
      currency,
    )
}

object TestInvoiceItem {
  def apply(
      id: String = randomId("invoiceItem"),
      price: Float = 20,
      serviceStartDate: LocalDate = LocalDate.now().minusDays(7),
      serviceEndDate: LocalDate = LocalDate.now().minusDays(7).plusMonths(12),
      chargeNumber: String = "1",
      productName: String = randomId("invoiceItemProductName"),
      subscriptionId: String = randomId("invoiceItemSubscriptionId"),
  ): InvoiceItem = InvoiceItem(
    id,
    price,
    serviceStartDate,
    serviceEndDate,
    chargeNumber,
    productName,
    subscriptionId,
  )
}
