package models

import play.api.libs.json._

case class MembershipAttributes(userId: String, tier: String, membershipNumber: String)

object MembershipAttributes {
  implicit val jsWrite = new Writes[MembershipAttributes] {
    override def writes(o: MembershipAttributes) = Json.obj(
      "tier" -> o.tier,
      "membershipNumber" -> o.membershipNumber
    )
  }
}
