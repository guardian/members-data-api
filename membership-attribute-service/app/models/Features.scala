package models

import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.mvc.Results.Ok

import scala.language.implicitConversions

object Features {
  implicit val jsWrite = Json.writes[Features]

  implicit def toResult(attrs: Features): Result =
    Ok(Json.toJson(attrs))

  def fromAttributes(attributes: Attributes) = {
    val adfree = attributes.isPaidTier

    Features(
      userId = Some(attributes.userId),
      adFree = adfree,
      adblockMessage = !adfree
    )
  }

  val unauthenticated = Features(None, adFree = false, adblockMessage = true)
}

case class Features(userId: Option[String], adFree: Boolean, adblockMessage: Boolean)
