package models

import org.joda.time.LocalDate
import play.api.libs.json._

case class MembershipAttributes(joinDate: LocalDate, tier: String, membershipNumber: String)

object MembershipAttributes {
  implicit val jsWrite = Json.writes[MembershipAttributes]
  implicit val jsRead = Json.reads[MembershipAttributes]
}
