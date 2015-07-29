package models

import org.joda.time.LocalDate
import play.api.libs.json._

case class MembershipAttributes(userId: String, joinDate: LocalDate, tier: String, membershipNumber: String)

object MembershipAttributes {
  implicit val jsWrite = Json.writes[MembershipAttributes]
}
