package services

import acceptance.data.TestBillingPreviewInvoiceItem
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import services.zuora.payment.PaymentService

class PaymentServiceTest extends Specification {

  "toPreviewInvoiceItem" should {

    "include tax in the price, matching the old SOAP amend-with-preview" in {
      val item = TestBillingPreviewInvoiceItem(chargeAmount = 10, taxAmount = 2)
      PaymentService.toPreviewInvoiceItem(item).price should_=== 12f
    }

    "carry the charge name and parse the service dates" in {
      val item = TestBillingPreviewInvoiceItem(
        serviceStartDate = "2027-06-25",
        serviceEndDate = "2028-06-24",
        chargeName = "Subscription",
      )
      val result = PaymentService.toPreviewInvoiceItem(item)
      result.chargeName should_=== "Subscription"
      result.serviceStartDate should_=== new LocalDate("2027-06-25")
      result.serviceEndDate should_=== new LocalDate("2028-06-24")
    }
  }
}
