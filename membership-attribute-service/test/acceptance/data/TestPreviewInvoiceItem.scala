package acceptance.data

import com.gu.zuora.soap.models.Queries.PreviewInvoiceItem
import org.joda.time.LocalDate

object TestPreviewInvoiceItem {
  def apply(): PreviewInvoiceItem = PreviewInvoiceItem(
    1f,
    new LocalDate(2024, 5, 14),
    new LocalDate(2024, 6, 14),
    "testProductId",
    "testProductRatePlanChargeId",
    "testChangeName",
    1f,
  )
}
