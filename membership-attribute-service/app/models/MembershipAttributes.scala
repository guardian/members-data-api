package models

import play.api.libs.json._

case class MembershipAttributes(tier: String, membershipNumber: String)

object MembershipAttributes {
  implicit val jsWrite = Json.writes[MembershipAttributes]
  implicit val jsRead = Json.reads[MembershipAttributes]
}
