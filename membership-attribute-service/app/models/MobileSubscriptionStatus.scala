package models

import org.joda.time.DateTime
import play.api.libs.json._

import scala.util.Try

case class MobileSubscriptionStatus(
  valid: Boolean,
  endDate: DateTime
)

object MobileSubscriptionStatus {
  private implicit val dateTimeReads: Reads[DateTime] = new Reads[DateTime] {
    override def reads(json: JsValue): JsResult[DateTime] = json match {
      case JsString(date) => Try(DateTime.parse(date)).map(res => JsSuccess(res)).getOrElse(JsError(s"Unable to parse Date $date"))
      case _ => JsError("Unable to parse date, was expecting a JsString")
    }
  }
  implicit val mobileSubscriptionStatusReads: Reads[MobileSubscriptionStatus] = Json.reads[MobileSubscriptionStatus]
}