package com.gu.memsub.subsv2.reads

import com.gu.memsub
import com.gu.memsub.Subscription._
import com.gu.memsub.promo.PromoCode
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.CommonReads._
import com.gu.memsub.{Price, PriceParser, PricingSummary}
import com.gu.zuora.rest.Readers._
import com.gu.zuora.rest.{Feature => RestFeature}
import org.joda.time.{DateTime, LocalDate}
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import scalaz.NonEmptyList
import scalaz.std.list._
import scalaz.std.option._
import scalaz.syntax.applicative._
import scalaz.syntax.traverse._

// since we don't have a stack to trace, we need to make our own
object Trace {

  implicit class Traceable[T](result: JsResult[T]) {
    def withTrace(message: String): JsResult[T] = result match {
      case JsError(e) => JsError(s"$message: $e")
      case success => success
    }
  }

}
import com.gu.memsub.subsv2.reads.Trace.Traceable

object SubJsonReads {

  private implicit val pricingSummaryReads: Reads[PricingSummary] = new Reads[PricingSummary] {
    override def reads(json: JsValue): JsResult[PricingSummary] = {

      // for subscriptions our pricing summary is a string i.e. 10GBP, for the catalog its an array
      val normalisedPricingList = json.validate[List[String]] orElse json.validate[String].map(List(_))

      val parsedPrices = normalisedPricingList.flatMap { priceStrings =>
        priceStrings
          .map(PriceParser.parse)
          .sequence[Option, Price] match {
          case Some(a) => JsSuccess(a)
          case _ => JsError(s"Failed to parse $normalisedPricingList")
        }
      }

      parsedPrices.map(priceList => priceList.map(p => p.currency -> p).toMap).map(PricingSummary)
    }
  }

  private val ratePlanChargeReads: Reads[RatePlanCharge] = (
    (__ \ "id").read[String].map(SubscriptionRatePlanChargeId) and
      (__ \ "productRatePlanChargeId").read[String].map(ProductRatePlanChargeId) and
      (__ \ "pricingSummary").read[PricingSummary] and
      (__ \ "billingPeriod").readNullable[ZBillingPeriod] and
      (__ \ "specificBillingPeriod").readNullable[Int] and
      (__ \ "endDateCondition").read[EndDateCondition] and
      (__ \ "upToPeriods").readNullable[Int] and
      (__ \ "upToPeriodsType").readNullable[UpToPeriodsType] and
      (__ \ "chargedThroughDate").readNullable[LocalDate] and
      (__ \ "effectiveStartDate").read[LocalDate] and
      (__ \ "effectiveEndDate").read[LocalDate]
  )(RatePlanCharge.apply _)

  private val ratePlanReads: Reads[RatePlan] = new Reads[RatePlan] {
    override def reads(json: JsValue): JsResult[RatePlan] = (
      (json \ "id").validate[String].map(RatePlanId) |@|
        (json \ "productRatePlanId").validate[String].map(ProductRatePlanId) |@|
        (json \ "productName").validate[String] |@|
        (json \ "lastChangeType").validateOpt[String] |@|
        (json \ "subscriptionProductFeatures").validate[List[RestFeature]] |@|
        (json \ "ratePlanCharges").validate[NonEmptyList[RatePlanCharge]](nelReads(niceListReads(ratePlanChargeReads)))
    )(RatePlan).withTrace("low-level-plan")
  }

  val multiSubJsonReads: Reads[List[Subscription]] = new Reads[List[Subscription]] {
    override def reads(json: JsValue): JsResult[List[Subscription]] =
      (json \ "subscriptions").validate(Reads.traversableReads[List, Subscription](implicitly, subscriptionReads))
  }

  private val lenientDateTimeReader: Reads[DateTime] =
    JodaReads.DefaultJodaDateTimeReads orElse Reads.IsoDateReads.map(new DateTime(_))

  val subscriptionReads: Reads[Subscription] = new Reads[Subscription] {
    override def reads(json: JsValue): JsResult[Subscription] = {

      json match {
        case o: JsObject =>
          (
            (__ \ "id").read[String].map(memsub.Subscription.Id) and
              (__ \ "subscriptionNumber").read[String].map(memsub.Subscription.SubscriptionNumber) and
              (__ \ "accountId").read[String].map(memsub.Subscription.AccountId) and
              (__ \ "contractEffectiveDate").read[LocalDate] and
              (__ \ "customerAcceptanceDate").read[LocalDate] and
              (__ \ "termEndDate").read[LocalDate] and
              (__ \ "status").read[String].map(_ == "Cancelled") and
              (__ \ "ratePlans").read[List[RatePlan]](niceListReads(ratePlanReads)) and
              (__ \ "ReaderType__c").readNullable[String].map(ReaderType.apply) and
              (__ \ "autoRenew").read[Boolean]
          )(memsub.subsv2.Subscription.apply _).reads(o)
        case e => JsError(s"Needed a JsObject, got ${e.getClass.getSimpleName}")
      }
    }
  }
}
