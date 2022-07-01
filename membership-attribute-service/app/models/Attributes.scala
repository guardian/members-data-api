package models

import com.github.nscala_time.time.OrderingImplicits._
import org.joda.time.LocalDate
import org.joda.time.LocalDate.now
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.Ok
import json.localDateWrites
import scala.language.implicitConversions
import scalaz.syntax.std.boolean._
import json._

case class ContentAccess(
    member: Boolean,
    paidMember: Boolean,
    recurringContributor: Boolean,
    digitalPack: Boolean,
    paperSubscriber: Boolean,
    guardianWeeklySubscriber: Boolean,
    guardianPatron: Boolean,
)

object ContentAccess {

  implicit val jsWrite = Json.writes[ContentAccess]
}

case class Attributes(
    UserId: String,
    Tier: Option[String] = None,
    RecurringContributionPaymentPlan: Option[String] = None,
    OneOffContributionDate: Option[LocalDate] = None,
    MembershipJoinDate: Option[LocalDate] = None,
    DigitalSubscriptionExpiryDate: Option[LocalDate] = None,
    PaperSubscriptionExpiryDate: Option[LocalDate] = None,
    GuardianWeeklySubscriptionExpiryDate: Option[LocalDate] = None,
    LiveAppSubscriptionExpiryDate: Option[LocalDate] = None,
    GuardianPatronExpiryDate: Option[LocalDate] = None,
    AlertAvailableFor: Option[String] = None,
) {
  lazy val isFriendTier = Tier.exists(_.equalsIgnoreCase("friend"))
  lazy val isSupporterTier = Tier.exists(_.equalsIgnoreCase("supporter"))
  lazy val isPartnerTier = Tier.exists(_.equalsIgnoreCase("partner"))
  lazy val isPatronTier = Tier.exists(_.equalsIgnoreCase("patron"))
  lazy val isStaffTier = Tier.exists(_.equalsIgnoreCase("staff"))
  lazy val isPaidTier = isSupporterTier || isPartnerTier || isPatronTier || isStaffTier
  lazy val isRecurringContributor = RecurringContributionPaymentPlan.isDefined
  lazy val isRecentOneOffContributor = OneOffContributionDate.exists(_.isAfter(now.minusMonths(3)))
  lazy val staffDigitalSubscriptionExpiryDate: Option[LocalDate] = Tier.exists(_.equalsIgnoreCase("staff")).option(now.plusDays(1))
  lazy val latestDigitalSubscriptionExpiryDate =
    Some(Set(staffDigitalSubscriptionExpiryDate, DigitalSubscriptionExpiryDate).flatten).filter(_.nonEmpty).map(_.max)
  lazy val digitalSubscriberHasActivePlan = latestDigitalSubscriptionExpiryDate.exists(_.isAfter(now))
  lazy val isPaperSubscriber = PaperSubscriptionExpiryDate.exists(_.isAfter(now))
  lazy val isGuardianWeeklySubscriber = GuardianWeeklySubscriptionExpiryDate.exists(_.isAfter(now))
  lazy val isPremiumLiveAppSubscriber = LiveAppSubscriptionExpiryDate.exists(_.isAfter(now))
  lazy val isGuardianPatron = GuardianPatronExpiryDate.exists(_.isAfter(now))

  lazy val contentAccess = ContentAccess(
    member = isPaidTier || isFriendTier,
    paidMember = isPaidTier,
    recurringContributor = isRecurringContributor,
    digitalPack = digitalSubscriberHasActivePlan || isPaperSubscriber || isGuardianPatron,
    paperSubscriber = isPaperSubscriber,
    guardianWeeklySubscriber = isGuardianWeeklySubscriber,
    guardianPatron = isGuardianPatron,
  )

  // show support messaging (in app & on dotcom) if they do NOT have any active products
  // TODO in future this could become more sophisticated (e.g. two weeks before their products expire)
  lazy val showSupportMessaging = !(
    isPaidTier
      || isRecurringContributor
      || isRecentOneOffContributor
      || digitalSubscriberHasActivePlan
      || isPaperSubscriber
      || isGuardianWeeklySubscriber
      || isPremiumLiveAppSubscriber
      || isGuardianPatron
  )

}

object Attributes {

  implicit val jsAttributesWrites: OWrites[Attributes] = (
    (__ \ "userId").write[String] and
      (__ \ "tier").writeNullable[String] and
      (__ \ "recurringContributionPaymentPlan").writeNullable[String] and
      (__ \ "oneOffContributionDate").writeNullable[LocalDate] and
      (__ \ "membershipJoinDate").writeNullable[LocalDate] and
      (__ \ "digitalSubscriptionExpiryDate").writeNullable[LocalDate] and
      (__ \ "paperSubscriptionExpiryDate").writeNullable[LocalDate] and
      (__ \ "guardianWeeklyExpiryDate").writeNullable[LocalDate] and
      (__ \ "liveAppSubscriptionExpiryDate").writeNullable[LocalDate] and
      (__ \ "guardianPatronExpiryDate").writeNullable[LocalDate] and
      (__ \ "alertAvailableFor").writeNullable[String]
  )(unlift(Attributes.unapply))
    .addNullableField("digitalSubscriptionExpiryDate", _.latestDigitalSubscriptionExpiryDate)
    .addField("showSupportMessaging", _.showSupportMessaging)
    .addField("contentAccess", _.contentAccess)
}

case class MembershipAttributes(
    UserId: String,
    Tier: String,
    AdFree: Option[Boolean] = None,
    ContentAccess: MembershipContentAccess,
)

object MembershipAttributes {

  implicit val jsWrite: OWrites[MembershipAttributes] = (
    (__ \ "userId").write[String] and
      (__ \ "tier").write[String] and
      (__ \ "adFree").writeNullable[Boolean] and
      (__ \ "contentAccess").write[MembershipContentAccess](MembershipContentAccess.jsWrite)
  )(unlift(MembershipAttributes.unapply))

  def fromAttributes(attr: Attributes): Option[MembershipAttributes] = for {
    tier <- attr.Tier
  } yield {
    MembershipAttributes(
      UserId = attr.UserId,
      Tier = tier,
      ContentAccess = MembershipContentAccess(
        member = attr.contentAccess.member,
        paidMember = attr.contentAccess.paidMember,
      ),
    )
  }

}

case class MembershipContentAccess(member: Boolean, paidMember: Boolean)

object MembershipContentAccess {
  implicit val jsWrite = Json.writes[MembershipContentAccess]
}
