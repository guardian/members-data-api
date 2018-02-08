package models

import com.github.nscala_time.time.OrderingImplicits._
import org.joda.time.LocalDate
import org.joda.time.LocalDate.now
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.Ok

import scala.language.implicitConversions
import scalaz.syntax.std.boolean._
import json._

case class ContentAccess(member: Boolean, paidMember: Boolean, recurringContributor: Boolean, digitalPack: Boolean)

object ContentAccess {
  implicit val jsWrite = Json.writes[ContentAccess]
}

case class Attributes(UserId: String,
                      Tier: Option[String] = None,
                      RecurringContributionPaymentPlan: Option[String] = None,
                      MembershipJoinDate: Option[LocalDate] = None,
                      DigitalSubscriptionExpiryDate: Option[LocalDate] = None,
                      MembershipNumber: Option[String] = None,
                      AdFree: Option[Boolean] = None) {
  lazy val isFriendTier = Tier.exists(_.equalsIgnoreCase("friend"))
  lazy val isSupporterTier = Tier.exists(_.equalsIgnoreCase("supporter"))
  lazy val isPartnerTier = Tier.exists(_.equalsIgnoreCase("partner"))
  lazy val isPatronTier = Tier.exists(_.equalsIgnoreCase("patron"))
  lazy val isStaffTier = Tier.exists(_.equalsIgnoreCase("staff"))
  lazy val isPaidTier = isSupporterTier || isPartnerTier || isPatronTier || isStaffTier
  lazy val isContributor = RecurringContributionPaymentPlan.isDefined
  lazy val staffDigitalSubscriptionExpiryDate: Option[LocalDate] = Tier.exists(_.equalsIgnoreCase("staff")).option(now.plusDays(1))
  lazy val latestDigitalSubscriptionExpiryDate =  Some(Set(staffDigitalSubscriptionExpiryDate, DigitalSubscriptionExpiryDate).flatten).filter(_.nonEmpty).map(_.max)
  lazy val digitalSubscriberHasActivePlan = latestDigitalSubscriptionExpiryDate.exists(_.isAfter(now))

  lazy val isAdFree = AdFree.exists(identity)

  lazy val contentAccess = ContentAccess(member = isPaidTier || isFriendTier, paidMember = isPaidTier, recurringContributor = isContributor, digitalPack = digitalSubscriberHasActivePlan)
}

case class ZuoraAttributes(UserId: String,
                           Tier: Option[String] = None,
                           RecurringContributionPaymentPlan: Option[String] = None,
                           MembershipJoinDate: Option[LocalDate] = None,
                           DigitalSubscriptionExpiryDate: Option[LocalDate] = None)

case class DynamoAttributes(UserId: String,
                            Tier: Option[String] = None,
                            RecurringContributionPaymentPlan: Option[String] = None,
                            MembershipJoinDate: Option[LocalDate] = None,
                            DigitalSubscriptionExpiryDate: Option[LocalDate] = None,
                            MembershipNumber: Option[String],
                            AdFree: Option[Boolean],
                            TTLTimestamp: Long) {
  lazy val isFriendTier = Tier.exists(_.equalsIgnoreCase("friend"))
  lazy val isSupporterTier = Tier.exists(_.equalsIgnoreCase("supporter"))
  lazy val isPartnerTier = Tier.exists(_.equalsIgnoreCase("partner"))
  lazy val isPatronTier = Tier.exists(_.equalsIgnoreCase("patron"))
  lazy val isStaffTier = Tier.exists(_.equalsIgnoreCase("staff"))
  lazy val isPaidTier = isSupporterTier || isPartnerTier || isPatronTier || isStaffTier
}

object DynamoAttributes {
  def asAttributes(dynamoAttributes: DynamoAttributes): Attributes = Attributes(
    UserId = dynamoAttributes.UserId,
    Tier = dynamoAttributes.Tier,
    RecurringContributionPaymentPlan = dynamoAttributes.RecurringContributionPaymentPlan,
    MembershipJoinDate = dynamoAttributes.MembershipJoinDate,
    DigitalSubscriptionExpiryDate = dynamoAttributes.DigitalSubscriptionExpiryDate,
    MembershipNumber = dynamoAttributes.MembershipNumber,
    AdFree = dynamoAttributes.AdFree
  )

  def asZuoraAttributes(dynamoAttributes: DynamoAttributes): ZuoraAttributes = ZuoraAttributes(
    UserId = dynamoAttributes.UserId,
    Tier = dynamoAttributes.Tier,
    RecurringContributionPaymentPlan = dynamoAttributes.RecurringContributionPaymentPlan,
    MembershipJoinDate = dynamoAttributes.MembershipJoinDate,
    DigitalSubscriptionExpiryDate = dynamoAttributes.DigitalSubscriptionExpiryDate
  )

}

object Attributes {

  implicit val jsAttributesWrites: OWrites[Attributes] = (
    (__ \ "userId").write[String] and
      (__ \ "tier").writeNullable[String] and
      (__ \ "recurringContributionPaymentPlan").writeNullable[String] and
      (__ \ "membershipJoinDate").writeNullable[LocalDate] and
      (__ \ "digitalSubscriptionExpiryDate").writeNullable[LocalDate] and
      (__ \ "membershipNumber").writeNullable[String] and
      (__ \ "adFree").writeNullable[Boolean]
  )(unlift(Attributes.unapply)).addNullableField("digitalSubscriptionExpiryDate", _.latestDigitalSubscriptionExpiryDate).addField("contentAccess", _.contentAccess)

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