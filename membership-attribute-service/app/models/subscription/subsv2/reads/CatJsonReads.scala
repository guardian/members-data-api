package models.subscription.subsv2.reads

import models.subscription.Subscription.{ProductId, ProductRatePlanChargeId, ProductRatePlanId, SubscriptionRatePlanChargeId}
import models.subscription._
import models.subscription.subsv2.reads.CommonReads._
import models.subscription.subsv2._
import play.api.libs.functional.syntax._
import play.api.libs.json.{Reads, _}

import scalaz.std.list._
import scalaz.syntax.traverse._

object CatJsonReads {

  implicit val catalogZuoraPlanChargeReads: Reads[ZuoraCharge] = (
    (__ \ "id").read[String].map(ProductRatePlanChargeId) and
      (__ \ "pricingSummary").read[PricingSummary] and
      (__ \ "billingPeriod").readNullable[ZBillingPeriod] and
      (__ \ "specificBillingPeriod").readNullable[Int] and
      (__ \ "model").read[String] and
      (__ \ "name").read[String] and
      (__ \ "type").read[String] and
      (__ \ "endDateCondition").read[EndDateCondition] and
      (__ \ "upToPeriods").readNullable[Int] and
      (__ \ "upToPeriodsType").readNullable[UpToPeriodsType]
  )(ZuoraCharge.apply(_, _, _, _, _, _, _, _, _, _))

  implicit val ProductReads = new Reads[Benefit] {
    override def reads(json: JsValue): JsResult[Benefit] = json match {
      case JsString(id) => Benefit.fromId(id).fold[JsResult[Benefit]](JsError(s"Bad product $id"))(e => JsSuccess(e))
      case a => JsError(s"Malformed product JSON, needed a string but got $a")
    }
  }

  implicit val catalogZuoraPlanBenefitReads: Reads[(ProductRatePlanChargeId, Benefit)] = (
    (__ \ "id").read[String].map(ProductRatePlanChargeId) and
      (__ \ "ProductType__c").read[Benefit]
  )(_ -> _)

  implicit val listOfProductsReads = new Reads[Map[ProductRatePlanChargeId, Benefit]] {
    override def reads(json: JsValue): JsResult[Map[ProductRatePlanChargeId, Benefit]] = json match {
      case JsArray(vals) =>
        vals
          .map(_.validate[(ProductRatePlanChargeId, Benefit)])
          .filter(_.isSuccess)
          .toList // bad things are happening here, we're chucking away errors
          .sequence[JsResult, (ProductRatePlanChargeId, Benefit)]
          .map(_.toMap)
      case _ => JsError("No valid benefits found")
    }
  }

  implicit val statusReads: Reads[Status] = new Reads[Status] {
    override def reads(json: JsValue): JsResult[Status] = json match {
      case JsString("Expired") => JsSuccess(Status.legacy)
      case JsString("Active") => JsSuccess(Status.current)
      case JsString("NotStarted") => JsSuccess(Status.upcoming)
      case a => JsError(s"Unknown status $a")
    }
  }

  val catalogZuoraPlanReads: ProductId => Reads[CatalogZuoraPlan] = pid =>
    new Reads[CatalogZuoraPlan] {
      override def reads(json: JsValue): JsResult[CatalogZuoraPlan] = {
        ((__ \ "id").read[String].map(ProductRatePlanId) and
          (__ \ "name").read[String] and
          (__ \ "description").readNullable[String].map(_.mkString) and
          Reads.pure(pid) and
          (__ \ "Saving__c").readNullable[String] and
          (__ \ "productRatePlanCharges").read[List[ZuoraCharge]](niceListReads(catalogZuoraPlanChargeReads)) and
          (__ \ "productRatePlanCharges").read[Map[ProductRatePlanChargeId, Benefit]](listOfProductsReads) and
          (__ \ "status").read[Status] and
          (__ \ "FrontendId__c").readNullable[String].map(_.flatMap(FrontendId.get)))(CatalogZuoraPlan.apply _).reads(json)
      }
    }

  implicit val catalogZuoraPlanListReads: Reads[List[CatalogZuoraPlan]] = new Reads[List[CatalogZuoraPlan]] {
    override def reads(json: JsValue): JsResult[List[CatalogZuoraPlan]] = json \ "products" match {
      case JsDefined(JsArray(products)) =>
        products.toList
          .map(p => (p \ "productRatePlans").validate[List[CatalogZuoraPlan]](niceListReads(catalogZuoraPlanReads(ProductId((p \ "id").as[String])))))
          .filter(_.isSuccess)
          .sequence[JsResult, List[CatalogZuoraPlan]]
          .map(_.flatten)
      case a => JsError(s"No product array found, got $a")
    }
  }
}
