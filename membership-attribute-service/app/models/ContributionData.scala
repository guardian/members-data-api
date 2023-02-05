package models

import java.util.Date

import anorm.{RowParser, Macro, ~}
import play.api.libs.json.{JsValue, Json, Writes}

case class ContributionData(
    created: Date,
    currency: String,
    amount: BigDecimal,
    status: String,
)

object ContributionData {
  implicit val writes = new Writes[ContributionData] {
    override def writes(o: ContributionData): JsValue = Json.obj(
      "created" -> o.created,
      "currency" -> o.currency.toString,
      "price" -> o.amount,
      "status" -> o.status,
    )
  }

  val contributionRowParser: RowParser[ContributionData] = Macro.indexedParser[ContributionData]
}
