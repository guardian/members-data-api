package com.gu.memsub.subsv2
import com.gu.memsub.BillingPeriod._
import com.gu.memsub.Subscription._
import com.gu.memsub._
import com.gu.zuora.rest.Feature
import org.joda.time.LocalDate
import play.api.libs.json._
import scalaz.{NonEmptyList, Validation}

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
  val values: Seq[EndDateCondition] = Seq(SubscriptionEnd, FixedPeriod, SpecificEndDate, OneTime)
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
  val values: Seq[ZBillingPeriod] = Seq(ZYear, ZTwoYears, ZThreeYears, ZMonth, ZQuarter, ZSemiAnnual, ZSpecificMonths, ZWeek, ZSpecificWeeks)
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
  val values: Seq[UpToPeriodsType] = Seq(BillingPeriods, Days, Weeks, Months, Years)
  implicit val reads: Reads[UpToPeriodsType] = ZuoraEnum.getReads(values, "invalid up to periods type value")
}

/** Low level model of a Zuora rate plan charge
  */
case class RatePlanCharge(
    id: SubscriptionRatePlanChargeId,
    productRatePlanChargeId: ProductRatePlanChargeId,
    pricing: PricingSummary,
    zBillingPeriod: Option[ZBillingPeriod],
    specificBillingPeriod: Option[Int] = None,
    endDateCondition: EndDateCondition,
    upToPeriods: Option[Int],
    upToPeriodsType: Option[UpToPeriodsType],
) {

  def billingPeriod: Validation[String, BillingPeriod] =
    (endDateCondition, zBillingPeriod) match {
      case (FixedPeriod, Some(ZSpecificWeeks))
          if specificBillingPeriod.exists(numberOfWeeks => numberOfWeeks == 6 || numberOfWeeks == 7) &&
            upToPeriods.contains(1) &&
            upToPeriodsType.contains(BillingPeriods) =>
        Validation.success[String, BillingPeriod](SixWeeks)
      case (FixedPeriod, Some(zPeriod))
          if upToPeriods.contains(1) &&
            upToPeriodsType.contains(BillingPeriods) =>
        zPeriod match {
          case ZYear => Validation.success[String, BillingPeriod](OneYear)
          case ZQuarter => Validation.success[String, BillingPeriod](ThreeMonths)
          case ZTwoYears => Validation.success[String, BillingPeriod](TwoYears)
          case ZThreeYears => Validation.success[String, BillingPeriod](ThreeYears)
          case ZSemiAnnual => Validation.success[String, BillingPeriod](SixMonths)
          case _ => Validation.f[BillingPeriod](s"zuora fixed period was $zPeriod")
        }
      case (SubscriptionEnd, Some(zPeriod)) =>
        zPeriod match {
          case ZMonth => Validation.success[String, BillingPeriod](Month)
          case ZQuarter => Validation.success[String, BillingPeriod](Quarter)
          case ZYear => Validation.success[String, BillingPeriod](Year)
          case ZSemiAnnual => Validation.success[String, BillingPeriod](SixMonthsRecurring)
          case _ => Validation.f[BillingPeriod](s"zuora recurring period was $zPeriod")
        }
      case (OneTime, None) => Validation.success[String, BillingPeriod](OneTimeChargeBillingPeriod) // This represents a one time rate plan charge
      case _ =>
        Validation.f[BillingPeriod](
          s"period =${zBillingPeriod} specificBillingPeriod=${specificBillingPeriod} uptoPeriodsType=${upToPeriodsType}, uptoPeriods=${upToPeriods}",
        )
    }

}

/** Low level model of a rate plan, as it appears on a subscription in Zuora
  */
case class RatePlan(
    id: RatePlanId,
    productRatePlanId: ProductRatePlanId,
    productName: String,
    lastChangeType: Option[String],
    features: List[Feature],
    chargedThroughDate: Option[LocalDate],
    ratePlanCharges: NonEmptyList[RatePlanCharge],
    start: LocalDate,
    end: LocalDate,
) {

  def totalChargesMinorUnit: Int =
    ratePlanCharges.map(c => (c.pricing.prices.head.amount * 100).toInt).list.toList.sum

  def chargesPrice: PricingSummary =
    ratePlanCharges
      .map(_.pricing)
      .list
      .toList
      .reduce((f1, f2) =>
        PricingSummary(
          f1.underlying.keySet.intersect(f2.underlying.keySet).map(c => c -> Price(f1.underlying(c).amount + f2.underlying(c).amount, c)).toMap,
        ),
      )

  def billingPeriod: Validation[String, BillingPeriod] = {
    val billingPeriods = ratePlanCharges.list.toList.map(c => c.billingPeriod).distinct
    billingPeriods match {
      case Nil => Validation.f[BillingPeriod]("No billing period found")
      case b :: Nil => b
      case _ => Validation.f[BillingPeriod]("Too many billing periods found")
    }
  }

  private def productRatePlan(catalog: Catalog) = {
    catalog.productRatePlans(productRatePlanId)
  }

  def product(catalog: Catalog): Product =
    catalog.products(productRatePlan(catalog).productId)

  def name(catalog: Catalog): String =
    productRatePlan(catalog).name

  def productType(catalog: Catalog): ProductType =
    productRatePlan(catalog).productType

}

/** Low level model of a product rate plan, as it appears in the Zuora product catalog
  */
case class ProductRatePlan(
    id: ProductRatePlanId,
    name: String,
    productId: ProductId,
    productRatePlanCharges: Map[ProductRatePlanChargeId, ProductRatePlanChargeProductType],
    private val productTypeOption: Option[ProductType],
) {
  lazy val productType: ProductType = productTypeOption.getOrElse(throw new RuntimeException("Product type is undefined for plan: " + name))
}

// ProductType is the catalog product level ProductType__c
case class ProductType(productTypeString: String) extends AnyVal
