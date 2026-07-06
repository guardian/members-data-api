package acceptance.data

import services.zuora.rest.ZuoraRestService.BillingPreviewInvoiceItem

object TestBillingPreviewInvoiceItem {
  def apply(
      subscriptionNumber: String = "A-S00000000",
      chargeAmount: Double = 10,
      taxAmount: Double = 0,
      serviceStartDate: String = "2024-01-01",
      serviceEndDate: String = "2024-02-01",
      chargeName: String = "chargeName",
  ): BillingPreviewInvoiceItem = BillingPreviewInvoiceItem(
    subscriptionNumber,
    chargeAmount,
    taxAmount,
    serviceStartDate,
    serviceEndDate,
    chargeName,
  )
}
