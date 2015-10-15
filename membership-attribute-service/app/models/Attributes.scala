package models

import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.Ok

import scala.language.implicitConversions

case class Attributes(userId: String, tier: String, membershipNumber: Option[String]) {
  require(tier.nonEmpty)
  require(userId.nonEmpty)

  lazy val isFriendTier = tier.equalsIgnoreCase("friend")
  lazy val isPaidTier = !isFriendTier
}

object Attributes {
  implicit val jsWrite = Json.writes[Attributes]

  implicit def toResult(attrs: Attributes): Result =
    Ok(Json.toJson(attrs))
}
