package com.gu.memsub.promo

import java.util.UUID

import com.github.nscala_time.time.Imports._
import com.gu.i18n.{Country, CountryGroup}
import com.gu.memsub.Product.Voucher
import com.gu.memsub.Subscription.ProductRatePlanId
import com.gu.memsub._
import com.gu.memsub.images.ResponsiveImageGroup
import com.gu.memsub.promo.CovariantIdObject.CovariantId
import org.joda.time.Days

import scala.language.higherKinds
import scalaz.\/
import scalaz.syntax.std.boolean._

case class Channel(get: String)
case class PromoCode(get: String) {
  override def toString: String = get
}
object NormalisedPromoCode {
  def safeFromString(str: String): PromoCode = PromoCode(str.filter(_.isLetterOrDigit).toUpperCase)
}

case class AppliesTo(productRatePlanIds: Set[ProductRatePlanId], countries: Set[Country])

case class CampaignCode(get: String) extends AnyVal

sealed trait CampaignGroup {
  val id: String
}
object CampaignGroup {

  case object Membership extends CampaignGroup {
    override val id = "membership"
  }
  case object DigitalPack extends CampaignGroup {
    override val id = "digitalpack"
  }
  case object Newspaper extends CampaignGroup {
    override val id = "newspaper"
  }
  case object GuardianWeekly extends CampaignGroup {
    override val id = "weekly"
  }

  def fromId(id: String): Option[CampaignGroup] = id match {
    case DigitalPack.id => Some(DigitalPack)
    case Membership.id => Some(Membership)
    case Newspaper.id => Some(Newspaper)
    case GuardianWeekly.id => Some(GuardianWeekly)
    case _ => None
  }
}
case class Campaign(code: CampaignCode, group: CampaignGroup, name: String, sortDate: Option[DateTime])

object AppliesTo {
  def ukOnly(prpIds: Set[ProductRatePlanId]) = AppliesTo(prpIds, Set(Country.UK))
  def all(prpIds: Set[ProductRatePlanId]) = AppliesTo(prpIds, CountryGroup.countries.toSet)
}

sealed trait PromoError {
  def msg: String
}

sealed trait PromoContext
sealed trait NewUsers extends PromoContext
sealed trait Upgrades extends PromoContext
sealed trait Renewal extends PromoContext
sealed trait Both extends NewUsers with Upgrades with Renewal

case class ValidPromotion[+C <: PromoContext](code: PromoCode, promotion: Promotion[PromotionType[C], Option, LandingPage]) {
  val trackingCode: Option[PromoCode] = if (promotion.isTracking) Some(code) else None
  val displayableCode: Option[PromoCode] = if (!promotion.isTracking) Some(code) else None
}

sealed trait PromotionType[+C <: PromoContext] {
  override def toString = getClass.getSimpleName
  val name: String
}

object PromotionType {
  val incentive = "incentive"
  val double = "double"
  val percentDiscount = "percent_discount"
  val freeTrial = "free_trial"
  val tracking = "tracking"
  val retention = "retention"
}

case class DoubleType[C <: PromoContext](a: PromotionType[C], b: PromotionType[C]) extends PromotionType[C] {
  override val name: String = PromotionType.double
}
case class Incentive(redemptionInstructions: String, termsAndConditions: Option[String], legalTerms: Option[String]) extends PromotionType[Both] {
  val name = PromotionType.incentive
}
case class PercentDiscount(durationMonths: Option[Int], amount: Double) extends PromotionType[Both] {
  val name = PromotionType.percentDiscount
}
case class FreeTrial(duration: Days) extends PromotionType[NewUsers] {
  val name = PromotionType.freeTrial
}
case object Tracking extends PromotionType[Both] {
  val name = PromotionType.tracking
}
case object Retention extends PromotionType[Renewal] {
  val name = PromotionType.retention
}

case object InvalidCountry extends PromoError {
  override val msg = "The promo code you supplied is not applicable in this country"
}

case object InvalidProductRatePlan extends PromoError {
  override val msg = "The promo code you supplied is not applicable for this product"
}

case object NotApplicable extends PromoError {
  override val msg = "This promotion is not applicable"
}

case object NoSuchCode extends PromoError {
  override val msg = "Unknown or expired promo code"
}

case object ExpiredPromotion extends PromoError {
  override val msg = "The promo code you supplied has expired"
}

case object PromotionNotActiveYet extends PromoError {
  override val msg = "The promo code you supplied is not active yet"
}

sealed trait SectionColour
case object Blue extends SectionColour
case object Grey extends SectionColour
case object White extends SectionColour

sealed trait LandingPage

sealed trait HeroImageAlignment
case object Top extends HeroImageAlignment
case object Bottom extends HeroImageAlignment
case object Centre extends HeroImageAlignment
case class HeroImage(image: ResponsiveImageGroup, alignment: HeroImageAlignment)

case class MembershipLandingPage(
    title: Option[String],
    subtitle: Option[String],
    description: Option[String],
    roundelHtml: Option[String],
    heroImage: Option[HeroImage],
    image: Option[ResponsiveImageGroup],
) extends LandingPage

case class DigitalPackLandingPage(
    title: Option[String],
    description: Option[String],
    roundelHtml: Option[String],
    image: Option[ResponsiveImageGroup],
    sectionColour: Option[SectionColour],
) extends LandingPage

case class NewspaperLandingPage(
    title: Option[String],
    description: Option[String],
    defaultProduct: String = Voucher.name,
    roundelHtml: Option[String],
) extends LandingPage

case class WeeklyLandingPage(
    title: Option[String],
    description: Option[String],
    roundelHtml: Option[String],
    image: Option[ResponsiveImageGroup],
    sectionColour: Option[SectionColour],
) extends LandingPage

case class Promotion[+T <: PromotionType[PromoContext], M[+_], +P <: LandingPage](
    uuid: UUID,
    name: String,
    description: String,
    appliesTo: AppliesTo,
    campaign: CampaignCode,
    channelCodes: Map[Channel, Set[PromoCode]],
    landingPage: M[P],
    starts: DateTime,
    expires: Option[DateTime],
    promotionType: T,
) {

  override def toString: String = {
    val allCodes = channelCodes.values.flatten
    s"$name codes:${allCodes.mkString(", ")} [$uuid]"
  }

  val isTracking = promotionType == Tracking

  def codes: Seq[PromoCode] = channelCodes.flatMap { case (_, codes) => codes }.toSeq

  private def toLegacyResponse(errors: Seq[PromoError]) = errors match {
    case Nil => \/.r[PromoError](())
    case errors => \/.l[Unit](errors.head)
  }

  def validateFor(prpId: ProductRatePlanId, country: Country, now: DateTime = DateTime.now()): PromoError \/ Unit =
    toLegacyResponse(validateAll(Some(prpId), country, now))

  def validate(country: Country, now: DateTime = DateTime.now()): PromoError \/ Unit = toLegacyResponse(validateAll(None, country, now))

  def validateAll(prpId: Option[ProductRatePlanId] = None, country: Country, now: DateTime = DateTime.now()): Seq[PromoError] =
    List(
      prpId.find(pId => promotionType != Tracking && !appliesTo.productRatePlanIds.contains(pId)).map(_ => InvalidProductRatePlan),
      (!appliesTo.countries.contains(country)).option(InvalidCountry),
      starts.isAfter(now).option(PromotionNotActiveYet),
      expires.find(e => e.isEqual(now) || e.isBefore(now)).map(_ => ExpiredPromotion),
    ).flatten
}

object CovariantIdObject {
  type CovariantId[+A] = A
}

object Promotion {

  type AnyPromotion = Promotion[PromotionType[PromoContext], Option, LandingPage]
  type PromoWithMembershipLandingPage = Promotion[PromotionType[PromoContext], CovariantId, MembershipLandingPage]
  type PromoWithDigitalPackLandingPage = Promotion[PromotionType[PromoContext], CovariantId, DigitalPackLandingPage]
  type PromoWithNewspaperLandingPage = Promotion[PromotionType[PromoContext], CovariantId, NewspaperLandingPage]
  type PromoWithWeeklyLandingPage = Promotion[PromotionType[PromoContext], CovariantId, WeeklyLandingPage]

  def apply[T <: PromotionType[PromoContext]](
      name: String,
      description: String,
      appliesTo: AppliesTo,
      campaign: CampaignCode,
      channelCodes: Map[Channel, Set[PromoCode]],
      landingPage: Option[LandingPage],
      starts: DateTime,
      expires: Option[DateTime],
      promotionType: T,
  ): Promotion[T, Option, LandingPage] = {

    Promotion(
      uuid = UUID.randomUUID(),
      name = name,
      description = description,
      appliesTo = appliesTo,
      campaign = campaign,
      channelCodes = channelCodes,
      landingPage = landingPage,
      starts = starts,
      expires = expires,
      promotionType = promotionType,
    )
  }

  implicit class PromoTypeCasts[A[+_], I <: LandingPage](in: Promotion[PromotionType[PromoContext], A, I]) {
    type PromoOpt[T <: PromotionType[PromoContext]] = Option[Promotion[T, A, I]]

    private def findType[B](i: PromotionType[PromoContext], f: PartialFunction[PromotionType[PromoContext], B]): Option[B] = i match {
      case DoubleType(a, b) => f.lift(a).orElse(f.lift(b))
      case c => f.lift(c)
    }

    def asDiscount: PromoOpt[PercentDiscount] = findType(in.promotionType, { case e: PercentDiscount => in.copy(promotionType = e) })
    def asFreeTrial: PromoOpt[FreeTrial] = findType(in.promotionType, { case e: FreeTrial => in.copy(promotionType = e) })
    def asIncentive: PromoOpt[Incentive] = findType(in.promotionType, { case e: Incentive => in.copy(promotionType = e) })
    def asTracking: PromoOpt[Tracking.type] = findType(in.promotionType, { case Tracking => in.copy(promotionType = Tracking) })
    def asRetention: PromoOpt[Retention.type] = findType(in.promotionType, { case Retention => in.copy(promotionType = Retention) })
  }

  implicit class PromoLandingPageCasts[A <: PromotionType[PromoContext], M[+_]](in: Promotion[A, Option, LandingPage]) {
    type PromoOpt[L <: LandingPage] = Option[Promotion[A, CovariantId, L]]
    def asDigitalPack: PromoOpt[DigitalPackLandingPage] = in.landingPage.collect { case f: DigitalPackLandingPage =>
      in.copy[A, CovariantId, DigitalPackLandingPage](landingPage = (f))
    }
    def asNewspaper: PromoOpt[NewspaperLandingPage] = in.landingPage.collect { case f: NewspaperLandingPage =>
      in.copy[A, CovariantId, NewspaperLandingPage](landingPage = (f))
    }
    def asWeekly: PromoOpt[WeeklyLandingPage] = in.landingPage.collect { case f: WeeklyLandingPage =>
      in.copy[A, CovariantId, WeeklyLandingPage](landingPage = (f))
    }
    def asMembership: PromoOpt[MembershipLandingPage] = in.landingPage.collect { case f: MembershipLandingPage =>
      in.copy[A, CovariantId, MembershipLandingPage](landingPage = (f))
    }
  }

  def asAnyPromotion[T <: PromotionType[PromoContext]](in: Promotion[T, CovariantId, LandingPage]): AnyPromotion =
    in.copy(landingPage = Some(in.landingPage))
}

object PercentDiscount {

  private implicit class MagnanimousDouble(d: Double) {
    def roundGenerously(precision: Int) = BigDecimal(d).setScale(2, BigDecimal.RoundingMode.UP).toDouble
  }

  implicit class PriceApplicator[M[+_]](in: PercentDiscount) {
    def applyDiscount(price: Price, bp: BillingPeriod): Price = {
      val (discountPercent, _) = getDiscountScaledToPeriod(in, bp)
      price.*(1f - (discountPercent.toFloat / 100f))
    }
  }

  def getDiscountScaledToPeriod(percentDiscount: PercentDiscount, billingPeriod: BillingPeriod): (Double, Double) = {
    val periodRatio = percentDiscount.durationMonths.fold(1.toDouble) { durationInMonths =>
      durationInMonths.toDouble / billingPeriod.monthsInPeriod.toDouble
    }
    val numberOfNewPeriods = Math.ceil(periodRatio)
    val newDiscountPercent = (percentDiscount.amount * periodRatio) / numberOfNewPeriods
    (newDiscountPercent.roundGenerously(2), numberOfNewPeriods)
  }
}
