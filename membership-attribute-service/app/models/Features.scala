package models

import org.joda.time.LocalDate
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
      adblockMessage = !attributes.isPaidTier,
      cardHasExpired = attributes.maybeCardHasExpired,
      cardExpires = attributes.cardExpires,
      membershipJoinDate = attributes.MembershipJoinDate
    )
  }

  val unauthenticated = Features(None, adFree = false, adblockMessage = true, None, None, None)
}

case class Features(
  userId: Option[String],
  adFree: Boolean,
  adblockMessage: Boolean,
  cardHasExpired: Option[Boolean],
  cardExpires: Option[LocalDate],
  membershipJoinDate: Option[LocalDate]
)
