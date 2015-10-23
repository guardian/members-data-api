package models

import configuration.Config
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.mvc.Results.Ok

import scala.language.implicitConversions

object Features {
  implicit val jsWrite = Json.writes[Features]

  implicit def toResult(attrs: Features): Result =
    Ok(Json.toJson(attrs))

  def fromAttributes(attributes: Attributes) = {
    // TODO: remove the second condition once the adfree feature is generally available to the public
    val adfree = attributes.isPaidTier && Config.preReleaseUsersIds.contains(attributes.userId)

    Features(
      userId = Some(attributes.userId),
      adFree = adfree,
      adblockMessage = !adfree
    )
  }

  val unauthenticated = Features(None, adFree = false, adblockMessage = true)
}

case class Features(userId: Option[String], adFree: Boolean, adblockMessage: Boolean)
