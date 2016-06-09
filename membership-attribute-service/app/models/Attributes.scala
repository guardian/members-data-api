package models

import json._
import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.Ok

import scala.language.implicitConversions

case class ContentAccess(member: Boolean, paidMember: Boolean)

object ContentAccess {
  implicit val jsWrite = Json.writes[ContentAccess]
}

case class Attributes(UserId: String, Tier: String, MembershipNumber: Option[String], PublicTier: Option[Boolean] = None) {
  require(Tier.nonEmpty)
  require(UserId.nonEmpty)

  lazy val allowsPublicTierDisplay = PublicTier.exists(identity)
  lazy val isFriendTier = Tier.equalsIgnoreCase("friend")
  lazy val isPaidTier = !isFriendTier

  lazy val contentAccess = ContentAccess(member = true, paidMember = isPaidTier) // we want to include staff!
}

object Attributes {
  implicit val jsWrite: Writes[Attributes] =
    Json.writes[Attributes].asInstanceOf[OWrites[Attributes]].addField("contentAccess", _.contentAccess)

  implicit def toResult(attrs: Attributes): Result =
    Ok(Json.toJson(attrs))
}
