package com.gu.memsub.subsv2
import com.gu.i18n.Currency
import Currency.GBP
import com.gu.memsub.Subscription.{
  ProductId,
  ProductRatePlanChargeId,
  ProductRatePlanId,
  RatePlanId,
  SubscriptionRatePlanChargeId,
  Feature => SubsFeature,
}

import scalaz.NonEmptyList
import com.gu.memsub._
import com.gu.zuora.rest.Feature

import scalaz.syntax.semigroup._
import PricingSummary._
import org.joda.time.LocalDate
import play.api.libs.json._

import Benefit._

trait ZuoraEnum {
  def id: String
}

object ZuoraEnum {
  def getReads[T <: ZuoraEnum](allValues: Seq[T], errorMessage: String): Reads[T] = new Reads[T] {
    override def reads(json: JsValue): JsResult[T] = json match {
      case JsString(zuoraId) => allValues.find(_.id == zuoraId).map(JsSuccess(_)).getOrElse(JsError(s"$errorMessage: $zuoraId"))
      case v => JsError(s"$errorMessage: $v")
    }
  }
}

sealed trait EndDateCondition extends ZuoraEnum

case object SubscriptionEnd extends EndDateCondition {
  override val id = "Subscription_End"
}

case object FixedPeriod extends EndDateCondition {
  override val id = "Fixed_Period"
}

case object SpecificEndDate extends EndDateCondition {
  override val id = "Specific_End_Date"
}

case object OneTime extends EndDateCondition {
  override val id = "One_Time"
}

object EndDateCondition {
  val values = Seq(SubscriptionEnd, FixedPeriod, SpecificEndDate, OneTime)
  implicit val reads: Reads[EndDateCondition] = ZuoraEnum.getReads(values, "invalid end date condition value")
}

sealed trait ZBillingPeriod extends ZuoraEnum

case object ZYear extends ZBillingPeriod {
  override val id = "Annual"
}

case object ZMonth extends ZBillingPeriod {
  override val id = "Month"
}

case object ZQuarter extends ZBillingPeriod {
  override val id = "Quarter"
}

case object ZSemiAnnual extends ZBillingPeriod {
  override val id = "Semi_Annual"
}

case object ZSpecificMonths extends ZBillingPeriod {
  override val id = "Specific_Months"
}

case object ZWeek extends ZBillingPeriod {
  override val id = "Week"
}

case object ZSpecificWeeks extends ZBillingPeriod {
  override val id = "Specific_Weeks"
}
case object ZTwoYears extends ZBillingPeriod {
  override val id = "Two_Years"
}

case object ZThreeYears extends ZBillingPeriod {
  override val id = "Three_Years"
}

object ZBillingPeriod {
  val values = Seq(ZYear, ZTwoYears, ZThreeYears, ZMonth, ZQuarter, ZSemiAnnual, ZSpecificMonths, ZWeek, ZSpecificWeeks)
  implicit val reads: Reads[ZBillingPeriod] = ZuoraEnum.getReads(values, "invalid billing period value")
}

sealed trait UpToPeriodsType extends ZuoraEnum

case object BillingPeriods extends UpToPeriodsType {
  override val id = "Billing_Periods"
}

case object Days extends UpToPeriodsType {
  override val id = "Days"
}

case object Weeks extends UpToPeriodsType {
  override val id = "Weeks"
}

case object Months extends UpToPeriodsType {
  override val id = "Months"
}

case object Years extends UpToPeriodsType {
  override val id = "Years"
}

object UpToPeriodsType {
  val values = Seq(BillingPeriods, Days, Weeks, Months, Years)
  implicit val reads: Reads[UpToPeriodsType] = ZuoraEnum.getReads(values, "invalid up to periods type value")
}

/** Low level model of a Zuora rate plan charge
  */
case class ZuoraCharge(
    id: SubscriptionRatePlanChargeId,
    productRatePlanChargeId: ProductRatePlanChargeId,
    pricing: PricingSummary,
    billingPeriod: Option[ZBillingPeriod],
    specificBillingPeriod: Option[Int] = None,
    model: String,
    name: String,
    `type`: String,
    endDateCondition: EndDateCondition,
    upToPeriods: Option[Int],
    upToPeriodsType: Option[UpToPeriodsType],
)

object ZuoraCharge {
  def apply(
      productRatePlanChargeId: ProductRatePlanChargeId,
      pricing: PricingSummary,
      billingPeriod: Option[ZBillingPeriod],
      specificBillingPeriod: Option[Int],
      model: String,
      name: String,
      `type`: String,
      endDateCondition: EndDateCondition,
      upToPeriods: Option[Int],
      upToPeriodsType: Option[UpToPeriodsType],
  ): ZuoraCharge = ZuoraCharge(
    SubscriptionRatePlanChargeId(""),
    productRatePlanChargeId,
    pricing,
    billingPeriod,
    specificBillingPeriod,
    model,
    name,
    `type`,
    endDateCondition,
    upToPeriods,
    upToPeriodsType,
  )
}

/** Low level model of a rate plan, as it appears on a subscription in Zuora
  */
case class SubscriptionZuoraPlan(
    id: RatePlanId,
    productRatePlanId: ProductRatePlanId,
    productName: String,
    lastChangeType: Option[String],
    features: List[Feature],
    chargedThroughDate: Option[LocalDate],
    charges: NonEmptyList[ZuoraCharge],
    start: LocalDate,
    end: LocalDate,
) {
  def price = charges.list.toList.flatMap { charge =>
    charge.pricing.prices.map(_.amount)
  }.sum
}

/** Low level model of a product rate plan, as it appears in the Zuora product catalog
  */
case class CatalogZuoraPlan(
    id: ProductRatePlanId,
    name: String,
    description: String,
    productId: ProductId,
    saving: Option[String],
    charges: List[ZuoraCharge],
    benefits: Map[ProductRatePlanChargeId, Benefit],
    status: Status,
    frontendId: Option[FrontendId],
    private val productTypeOption: Option[String],
) {
  lazy val productType = productTypeOption.getOrElse(throw new RuntimeException("Product type is undefined for plan: " + name))
}

sealed trait FrontendId {
  def name: String
}
object FrontendId {

  case object OneYear extends FrontendId { val name = "OneYear" }
  case object ThreeMonths extends FrontendId { val name = "ThreeMonths" }
  case object Monthly extends FrontendId { val name = "Monthly" }
  case object Quarterly extends FrontendId { val name = "Quarterly" }
  case object Yearly extends FrontendId { val name = "Yearly" }
  case object Introductory extends FrontendId { val name = "Introductory" }
  case object SixWeeks extends FrontendId { val name = "SixWeeks" }

  val all = List(OneYear, ThreeMonths, Monthly, Quarterly, Yearly, Introductory, SixWeeks)

  def get(jsonString: String): Option[FrontendId] =
    all.find(_.name == jsonString)

}

case class Catalog(
    map: Map[ProductRatePlanId, CatalogZuoraPlan],
)

/** A higher level representation of a number of Zuora rate plan charges
  */
sealed trait RatePlanChargeList {
  def benefits: NonEmptyList[Benefit]
  def currencies: Set[Currency] = price.currencies
  def billingPeriod: BillingPeriod
  def price: PricingSummary
  def subRatePlanChargeId: SubscriptionRatePlanChargeId
}

/** Same as above but we must have exactly one charge, giving us exactly one benefit This is used for supporter, partner, patron and digital pack subs
  */
case class RatePlanCharge[+B <: Benefit, +BP <: BillingPeriod](
    benefit: B,
    billingPeriod: BP,
    price: PricingSummary,
    chargeId: ProductRatePlanChargeId,
    subRatePlanChargeId: SubscriptionRatePlanChargeId,
) extends RatePlanChargeList {
  def benefits = NonEmptyList(benefit)
}

/** Paper plans will have lots of rate plan charges, but the general structure of them is that they'll give you the paper on a bunch of days, and if
  * you're on a plus plan you'll have a digipack
  */
case class PaperCharges(dayPrices: Map[PaperDay, PricingSummary], digipack: Option[PricingSummary]) extends RatePlanChargeList {
  def benefits = NonEmptyList.fromSeq[Benefit](dayPrices.keys.head, dayPrices.keys.tail.toSeq ++ digipack.map(_ => Digipack))
  def price: PricingSummary = (dayPrices.values.toSeq ++ digipack.toSeq).reduce(_ |+| _)
  override def billingPeriod: BillingPeriod = BillingPeriod.Month
  def chargedDays = dayPrices.filterNot(_._2.isFree).keySet // Non-subscribed-to papers are priced as Zero on multi-day subs
  val subRatePlanChargeId = SubscriptionRatePlanChargeId("")
}

/** Supporter Plus V2 has two rate plan charges, one for the subscription element and one for the additional contribution.
  */
case class SupporterPlusCharges(billingPeriod: BillingPeriod, pricingSummaries: List[PricingSummary]) extends RatePlanChargeList {

  val subRatePlanChargeId = SubscriptionRatePlanChargeId("")
  override def price: PricingSummary = pricingSummaries.reduce(_ |+| _)
  override def benefits: NonEmptyList[Benefit] = NonEmptyList(SupporterPlus)
}

case class RatePlan(
    id: RatePlanId,
    productRatePlanId: ProductRatePlanId,
    name: String,
    description: String,
    productName: String,
    lastChangeType: Option[String],
    productType: String,
    product: Product,
    features: List[SubsFeature],
    charges: RatePlanChargeList,
    chargedThrough: Option[
      LocalDate,
    ], // this is None if the sub hasn't been billed yet (on a free trial) or if you have been billed it is the date at which you'll next be billed
    start: LocalDate,
    end: LocalDate,
)

case class ProductRatePlan[+P <: Product, +C <: RatePlanChargeList, +S <: Status](
    id: ProductRatePlanId,
    product: P,
    name: String,
    description: String,
    saving: Option[Int],
    charges: C,
    s: S,
)
