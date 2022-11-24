package models

import java.util.Date
import anorm.{Macro, RowParser, ~}
import com.gu.i18n.Currency
import play.api.libs.json.{JsValue, Json, Writes}

case class ContributionData(
    created: Date,
    currency: String,
    amount: BigDecimal,
    status: String,
    payment_provider: String,
    refunded: Option[Date],
    payment_id: String,
)

object ContributionData {
  implicit val contributionDataWrites = new Writes[ContributionData] {
    override def writes(o: ContributionData): JsValue = Json.obj(
      "created" -> o.created,
      "currency" -> o.currency,
      "currencyIdentifier" -> Currency.fromString(o.currency).map(_.identifier),
      "amount" -> o.amount,
      "status" -> o.status,
      "payment_provider" -> o.payment_provider,
      "refunded" -> o.refunded,
      "payment_id" -> o.payment_id,
    )
  }

  val contributionRowParser: RowParser[ContributionData] = Macro.indexedParser[ContributionData]
}
