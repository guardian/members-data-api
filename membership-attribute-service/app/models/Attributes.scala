package models

import json._
import org.joda.time.LocalDate
import org.joda.time.LocalDate.now
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.Ok

import scala.language.implicitConversions

case class ContentAccess(member: Boolean, paidMember: Boolean)

object ContentAccess {
  implicit val jsWrite = Json.writes[ContentAccess]
}

case class Attributes(
                       UserId: String,
                       Tier: String,
                       MembershipNumber: Option[String],
                       AdFree: Option[Boolean] = None,
                       CardExpirationMonth: Option[Int] = None,
                       CardExpirationYear: Option[Int] = None,
                       Contributor: Option[String] = None) {

  require(Tier.nonEmpty)
  require(UserId.nonEmpty)

  lazy val isFriendTier = Tier.equalsIgnoreCase("friend")
  lazy val isPaidTier = !isFriendTier
  lazy val isAdFree = AdFree.exists(identity)
  lazy val isContributor = Contributor.isDefined

  lazy val contentAccess = ContentAccess(member = true, paidMember = isPaidTier) // we want to include staff!

  lazy val cardExpires = for {
    year <- CardExpirationYear
    month <- CardExpirationMonth
  } yield new LocalDate(year, month, 1).plusMonths(1).minusDays(1)

  lazy val maybeCardHasExpired = cardExpires.map(_.isBefore(now))
}

object Attributes {

  implicit val jsWrite: OWrites[Attributes] = (
    (__ \ "userId").write[String] and
    (__ \ "tier").write[String] and
    (__ \ "membershipNumber").writeNullable[String] and
    (__ \ "adFree").writeNullable[Boolean] and
    (__ \ "cardExpirationMonth").writeNullable[Int] and
    (__ \ "cardExpirationYear").writeNullable[Int] and
    (__ \ "Contributor").writeNullable[String]
  )(unlift(Attributes.unapply)).addField("contentAccess", _.contentAccess)

  implicit def toResult(attrs: Attributes): Result =
    Ok(Json.toJson(attrs))
}
