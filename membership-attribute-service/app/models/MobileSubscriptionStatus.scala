package models

import org.joda.time.DateTime
import play.api.libs.json._

import scala.util.Try

case class MobileSubscriptionStatus(
    valid: Boolean,
    to: DateTime,
    platform: Option[Platform] = None,
)

object MobileSubscriptionStatus {
  private implicit val dateTimeReads: Reads[DateTime] = new Reads[DateTime] {
    override def reads(json: JsValue): JsResult[DateTime] = json match {
      case JsString(date) => Try(DateTime.parse(date)).map(res => JsSuccess(res)).getOrElse(JsError(s"Unable to parse Date $date"))
      case _ => JsError("Unable to parse date, was expecting a JsString")
    }
  }
  
  private implicit val dateTimeWrites: Writes[DateTime] = new Writes[DateTime] {
    override def writes(dateTime: DateTime): JsValue = JsString(dateTime.toString)
  }
  
  // Import Platform JSON readers/writers
  import Platform._
  
  implicit val mobileSubscriptionStatusReads: Reads[MobileSubscriptionStatus] = Json.reads[MobileSubscriptionStatus]
  implicit val mobileSubscriptionStatusWrites: Writes[MobileSubscriptionStatus] = Json.writes[MobileSubscriptionStatus]
}
