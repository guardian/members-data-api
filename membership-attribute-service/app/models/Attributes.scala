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

case class Attributes(userId: String, tier: String, membershipNumber: Option[String], publicTierOptIn: Option[Boolean] = None) {
  require(tier.nonEmpty)
  require(userId.nonEmpty)

  lazy val allowsPublicTierDisplay = publicTierOptIn.exists(identity)
  lazy val isFriendTier = tier.equalsIgnoreCase("friend")
  lazy val isPaidTier = !isFriendTier

  lazy val contentAccess = ContentAccess(member = true, paidMember = isPaidTier) // we want to include staff!
}

object Attributes {
  implicit val jsWrite: Writes[Attributes] =
    Json.writes[Attributes].asInstanceOf[OWrites[Attributes]].addField("contentAccess", _.contentAccess)

  implicit def toResult(attrs: Attributes): Result =
    Ok(Json.toJson(attrs))
}
