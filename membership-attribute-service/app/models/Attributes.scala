package models

import com.gu.memsub.Benefit.PaidMemberTier
import com.gu.memsub.subsv2.CatalogPlan.Contributor
import json._
import org.joda.time.LocalDate
import org.joda.time.LocalDate.now
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.Ok

import scala.language.implicitConversions

case class ContentAccess(member: Boolean, paidMember: Boolean, contributor: Boolean)

object ContentAccess {
  implicit val jsWrite = Json.writes[ContentAccess]
}

case class Attributes(
                       UserId: String,
                       Tier: Option[String] = None,
                       MembershipNumber: Option[String],
                       AdFree: Option[Boolean] = None,
                       CardExpirationMonth: Option[Int] = None,
                       CardExpirationYear: Option[Int] = None,
                       ContributionFrequency: Option[String] = None) {

  require(UserId.nonEmpty)

  lazy val isFriendTier = Tier.exists(_.equalsIgnoreCase("friend"))
  lazy val isSupporterTier = Tier.exists(_.equalsIgnoreCase("supporter"))
  lazy val isPartnerTier = Tier.exists(_.equalsIgnoreCase("partner"))
  lazy val isPatronTier = Tier.exists(_.equalsIgnoreCase("patron"))
  lazy val isStaffTier = Tier.exists(_.equalsIgnoreCase("staff"))
  lazy val isPaidTier = isSupporterTier || isPartnerTier || isPatronTier || isStaffTier
  lazy val isAdFree = AdFree.exists(identity)
  lazy val isContributor = ContributionFrequency.isDefined

  lazy val contentAccess = ContentAccess(member = isPaidTier || isFriendTier, paidMember = isPaidTier, contributor = isContributor) // we want to include staff!

  lazy val cardExpires = for {
    year <- CardExpirationYear
    month <- CardExpirationMonth
  } yield new LocalDate(year, month, 1).plusMonths(1).minusDays(1)

  lazy val maybeCardHasExpired = cardExpires.map(_.isBefore(now))
}

object Attributes {

  implicit val jsWrite: OWrites[Attributes] = (
    (__ \ "userId").write[String] and
    (__ \ "tier").writeNullable[String] and
    (__ \ "membershipNumber").writeNullable[String] and
    (__ \ "adFree").writeNullable[Boolean] and
    (__ \ "cardExpirationMonth").writeNullable[Int] and
    (__ \ "cardExpirationYear").writeNullable[Int] and
    (__ \ "contributionFrequency").writeNullable[String]
  )(unlift(Attributes.unapply)).addField("contentAccess", _.contentAccess)



  implicit def toResult(attrs: Attributes): Result =
    Ok(Json.toJson(attrs))
}



case class MembershipAttributes(
                       UserId: String,
                       Tier: Option[String] = None,
                       MembershipNumber: Option[String],
                       AdFree: Option[Boolean] = None,
                       ContentAccess : MembershipContentAccess)

object MembershipAttributes {

  implicit val jsWrite: OWrites[MembershipAttributes] = (
    (__ \ "userId").write[String] and
    (__ \ "tier").writeNullable[String] and
    (__ \ "membershipNumber").writeNullable[String] and
    (__ \ "adFree").writeNullable[Boolean] and
    (__ \ "contentAccess").write[MembershipContentAccess](MembershipContentAccess.jsWrite)
   )(unlift(MembershipAttributes.unapply))

  implicit def toResult(attrs: MembershipAttributes): Result =
    Ok(Json.toJson(attrs))

  def fromAttributes( attr:Attributes) =
    MembershipAttributes(
      UserId = attr.UserId,
      Tier = attr.Tier,
      MembershipNumber = attr.MembershipNumber,
      AdFree = attr.AdFree,
      ContentAccess =  MembershipContentAccess(
        member = attr.contentAccess.member,
        paidMember = attr.contentAccess.paidMember
      )
    )

}


case class MembershipContentAccess(member: Boolean, paidMember: Boolean)

object MembershipContentAccess {
  implicit val jsWrite = Json.writes[MembershipContentAccess]
}