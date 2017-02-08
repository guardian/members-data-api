package models

import configuration.Config
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.mvc.Results.Ok

import scala.language.implicitConversions

object Features {
  implicit val jsWrite = Json.writes[Features]

  implicit def toResult(attrs: Features): Result =
    Ok(Json.toJson(attrs)).withHeaders(
      "X-Gu-Ad-Free" -> attrs.adFree.toString
    )

  def fromAttributes(attributes: Attributes) = {
    Features(
      userId = Some(attributes.UserId),
      adFree = attributes.isAdFree,
      adblockMessage = !attributes.isPaidTier
    )
  }

  val unauthenticated = Features(None, adFree = false, adblockMessage = true)
}

case class Features(userId: Option[String], adFree: Boolean, adblockMessage: Boolean)
