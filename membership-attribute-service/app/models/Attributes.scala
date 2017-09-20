package models


import json._
import org.joda.time.LocalDate
import org.joda.time.LocalDate.now
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.Ok
import scalaz.syntax.std.boolean._

import scala.language.implicitConversions

case class ContentAccess(member: Boolean, paidMember: Boolean, recurringContributor: Boolean, digitalPack: Boolean)

object ContentAccess {
  implicit val jsWrite = Json.writes[ContentAccess]
}

case class CardDetails(last4: String, expirationMonth: Int, expirationYear: Int, forProduct: String) {
  def asLocalDate: LocalDate = new LocalDate(expirationYear, expirationMonth, 1).plusMonths(1).minusDays(1)
}

object CardDetails {
  def fromStripeCard(stripeCard: com.gu.stripe.Stripe.Card, product: String) = {
    CardDetails(last4 = stripeCard.last4, expirationMonth = stripeCard.exp_month, expirationYear = stripeCard.exp_year, forProduct = product)
  }
}

case class Wallet(membershipCard: Option[CardDetails] = None, recurringContributionCard: Option[CardDetails] = None) {
  val expiredCards: Seq[CardDetails] = Seq(membershipCard, recurringContributionCard).flatten.filter(_.asLocalDate.isBefore(now))
  // TODO - val cardsExpiringSoon - I assume within 1 calendar month?
}

object Wallet {

  implicit val cardWriter = Json.writes[CardDetails]

  implicit val jsWrite = Json.writes[Wallet]

}

case class Attributes(
  UserId: String,
  Tier: Option[String] = None,
  MembershipNumber: Option[String] = None,
  AdFree: Option[Boolean] = None,
  Wallet: Option[Wallet] = None,
  RecurringContributionPaymentPlan: Option[String] = None,
  MembershipJoinDate: Option[LocalDate] = None,
  DigitalSubscriptionExpiryDate: Option[LocalDate] = None
) {

  require(UserId.nonEmpty)

  lazy val isFriendTier = Tier.exists(_.equalsIgnoreCase("friend"))
  lazy val isSupporterTier = Tier.exists(_.equalsIgnoreCase("supporter"))
  lazy val isPartnerTier = Tier.exists(_.equalsIgnoreCase("partner"))
  lazy val isPatronTier = Tier.exists(_.equalsIgnoreCase("patron"))
  lazy val isStaffTier = Tier.exists(_.equalsIgnoreCase("staff"))
  lazy val isPaidTier = isSupporterTier || isPartnerTier || isPatronTier || isStaffTier
  lazy val isAdFree = AdFree.exists(identity)
  lazy val isContributor = RecurringContributionPaymentPlan.isDefined
  lazy val staffExpiryDate: Option[LocalDate] = Tier.exists(_.equalsIgnoreCase("staff")).option(now.plusDays(1))
  lazy val logicalDigitalSubscriptionExpiryDate =  Some(Set(staffExpiryDate, DigitalSubscriptionExpiryDate).flatten).filter(_.nonEmpty).map(_.max)
  lazy val digitalSubscriberHasActivePlan = logicalDigitalSubscriptionExpiryDate.exists(_.isAfter(now))

  lazy val contentAccess = ContentAccess(member = isPaidTier || isFriendTier, paidMember = isPaidTier, recurringContributor = isContributor, digitalPack = digitalSubscriberHasActivePlan)
}

object Attributes {

  implicit val jsWrite: OWrites[Attributes] = (
    (__ \ "userId").write[String] and
    (__ \ "tier").writeNullable[String] and
    (__ \ "membershipNumber").writeNullable[String] and
    (__ \ "adFree").writeNullable[Boolean] and
    (__ \ "wallet").writeNullable[Wallet](Wallet.jsWrite) and
    (__ \ "recurringContributionPaymentPlan").writeNullable[String] and
    (__ \ "membershipJoinDate").writeNullable[LocalDate] and
    (__ \ "digitalSubscriptionExpiryDate").writeNullable[LocalDate]
    )(unlift(Attributes.unapply)).addField("digitalSubscriptionExpiryDate", _.logicalDigitalSubscriptionExpiryDate).addField("contentAccess", _.contentAccess)

  implicit def toResult(attrs: Attributes): Result =
    Ok(Json.toJson(attrs))
}



case class MembershipAttributes(
  UserId: String,
  Tier: String,
  MembershipNumber: Option[String],
  AdFree: Option[Boolean] = None,
  ContentAccess : MembershipContentAccess
)

object MembershipAttributes {

  implicit val jsWrite: OWrites[MembershipAttributes] = (
    (__ \ "userId").write[String] and
    (__ \ "tier").write[String] and
    (__ \ "membershipNumber").writeNullable[String] and
    (__ \ "adFree").writeNullable[Boolean] and
    (__ \ "contentAccess").write[MembershipContentAccess](MembershipContentAccess.jsWrite)
   )(unlift(MembershipAttributes.unapply))

  def fromAttributes(attr: Attributes): Option[MembershipAttributes] = for {
    tier <- attr.Tier
  } yield {
    MembershipAttributes(
      UserId = attr.UserId,
      Tier = tier,
      MembershipNumber = attr.MembershipNumber,
      AdFree = attr.AdFree,
      ContentAccess = MembershipContentAccess(
        member = attr.contentAccess.member,
        paidMember = attr.contentAccess.paidMember
      )
    )
  }

}

case class MembershipContentAccess(member: Boolean, paidMember: Boolean)

object MembershipContentAccess {
  implicit val jsWrite = Json.writes[MembershipContentAccess]
}