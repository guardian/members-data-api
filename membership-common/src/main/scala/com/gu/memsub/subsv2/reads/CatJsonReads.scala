package com.gu.memsub.subsv2.reads

import com.gu.memsub.Subscription.{ProductId, ProductRatePlanChargeId, ProductRatePlanId}
import com.gu.memsub._
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.CommonReads._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scalaz.std.list._
import scalaz.syntax.traverse._

object CatJsonReads {

  private implicit val ProductReads: Reads[Benefit] = new Reads[Benefit] {
    override def reads(json: JsValue): JsResult[Benefit] = json match {
      case JsString(id) => Benefit.fromId(id).fold[JsResult[Benefit]](JsError(s"Bad product $id"))(e => JsSuccess(e))
      case a => JsError(s"Malformed product JSON, needed a string but got $a")
    }
  }

  private implicit val catalogZuoraPlanBenefitReads: Reads[(ProductRatePlanChargeId, Benefit)] = (
    (__ \ "id").read[String].map(ProductRatePlanChargeId) and
      (__ \ "ProductType__c").read[Benefit]
  )(_ -> _)

  private val listOfProductsReads = new Reads[Map[ProductRatePlanChargeId, Benefit]] {
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

  private def catalogZuoraPlanReads(productType: Option[ProductType], pid: ProductId): Reads[CatalogZuoraPlan] =
    (json: JsValue) => {
      ((__ \ "id").read[String].map(ProductRatePlanId) and
        (__ \ "name").read[String] and
        Reads.pure(pid) and
        (__ \ "productRatePlanCharges").read[Map[ProductRatePlanChargeId, Benefit]](listOfProductsReads) and
        Reads.pure(productType))(CatalogZuoraPlan.apply _).reads(json)
    }

  val catalogZuoraPlanListReads: Reads[List[CatalogZuoraPlan]] =
    (json: JsValue) =>
      json \ "products" match {
        case JsDefined(JsArray(products)) =>
          products.toList
            .map { product =>
              val productId = (product \ "id").as[String]
              val productType = (product \ "ProductType__c").asOpt[String].map(ProductType)
              val reads = catalogZuoraPlanReads(productType, ProductId(productId))
              (product \ "productRatePlans").validate[List[CatalogZuoraPlan]](niceListReads(reads))
            }
            .filter(_.isSuccess)
            .sequence[JsResult, List[CatalogZuoraPlan]]
            .map(_.flatten)
        case a => JsError(s"No product array found, got $a")
      }
}
