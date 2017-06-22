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
    // TODO - confirm which notification we're doing first: 'your card is expiring soon' or 'your card has expired'

    // It's too confusing to tell the customer about multiple card expirations, so just take the first
    val maybeExpiredCard = attributes.Wallet.flatMap(_.expiredCards.headOption)

    Features(
      userId = Some(attributes.UserId),
      adFree = attributes.isAdFree,
      adblockMessage = !attributes.isPaidTier,
      cardHasExpiredForProduct = maybeExpiredCard.map(_.forProduct),
      cardExpiredOn = maybeExpiredCard.map(_.asLocalDate),
      membershipJoinDate = attributes.MembershipJoinDate
    )
  }

  val unauthenticated = Features(None, adFree = false, adblockMessage = true, None, None, None)
}

case class Features(
  userId: Option[String],
  adFree: Boolean,
  adblockMessage: Boolean,
  cardHasExpiredForProduct: Option[String],
  cardExpiredOn: Option[LocalDate],
  membershipJoinDate: Option[LocalDate]
)
