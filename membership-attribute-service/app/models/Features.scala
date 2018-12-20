package models

import org.joda.time.LocalDate
import play.api.libs.json.{Json}
import play.api.mvc.Result
import play.api.mvc.Results.Ok
import json.localDateWrites
import scala.language.implicitConversions

object Features {

  implicit val jsWrite = Json.writes[Features]

  implicit def toResult(attrs: Features): Result =
    Ok(Json.toJson(attrs))

  def fromAttributes(attributes: Attributes) = {
    Features(
      userId = Some(attributes.UserId),
      adblockMessage = !attributes.isPaidTier,
      membershipJoinDate = attributes.MembershipJoinDate
    )
  }

  val unauthenticated = Features(None, adblockMessage = true, None)
}

case class Features(
  userId: Option[String],
  adblockMessage: Boolean,
  membershipJoinDate: Option[LocalDate]
)
