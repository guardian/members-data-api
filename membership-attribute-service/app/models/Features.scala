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
    // TODO: Once this officially launches, this should be:
    // attributes.isPaidTier && (user has opted INTO the ad free experience)
    val adfreeEnabled = attributes.isPaidTier && Config.preReleaseUsersIds.contains(attributes.UserId)
    
    Features(
      userId = Some(attributes.UserId),
      adFree = adfreeEnabled,
      adblockMessage = !(attributes.isPaidTier)
    )
  }

  val unauthenticated = Features(None, adFree = false, adblockMessage = true)
}

case class Features(userId: Option[String], adFree: Boolean, adblockMessage: Boolean)
