package models

import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.Ok

import scala.language.implicitConversions

case class MembershipAttributes(userId: String, tier: String, membershipNumber: Option[String]) {
  require(tier.nonEmpty && userId.nonEmpty)
  lazy val isFriendTier = tier.equalsIgnoreCase("friend")
  lazy val isPaidTier = !isFriendTier
}

object MembershipAttributes {
  implicit val jsWrite = new Writes[MembershipAttributes] {
    override def writes(o: MembershipAttributes) = Json.obj(
      "tier" -> o.tier,
      "membershipNumber" -> o.membershipNumber
    )
  }

  implicit def membershipAttributes2Result(attrs: MembershipAttributes): Result =
    Ok(Json.toJson(attrs))
}
