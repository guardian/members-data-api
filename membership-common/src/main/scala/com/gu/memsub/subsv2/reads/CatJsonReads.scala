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

  private implicit val productRatePlanChargeProductTypeReads: Reads[ProductRatePlanChargeProductType] = new Reads[ProductRatePlanChargeProductType] {
    override def reads(json: JsValue): JsResult[ProductRatePlanChargeProductType] = json match {
      case JsString(id) =>
        ProductRatePlanChargeProductType.fromId(id).fold[JsResult[ProductRatePlanChargeProductType]](JsError(s"Bad product $id"))(e => JsSuccess(e))
      case a => JsError(s"Malformed product JSON, needed a string but got $a")
    }
  }

  private implicit val productRatePlanChargeReads: Reads[(ProductRatePlanChargeId, ProductRatePlanChargeProductType)] = (
    (__ \ "id").read[String].map(ProductRatePlanChargeId) and
      (__ \ "ProductType__c").read[ProductRatePlanChargeProductType]
  )(_ -> _)

  private val productRatePlanChargesReads = new Reads[Map[ProductRatePlanChargeId, ProductRatePlanChargeProductType]] {
    override def reads(json: JsValue): JsResult[Map[ProductRatePlanChargeId, ProductRatePlanChargeProductType]] = json match {
      case JsArray(vals) =>
        vals
          .map(_.validate[(ProductRatePlanChargeId, ProductRatePlanChargeProductType)])
          .filter(_.isSuccess)
          .toList // bad things are happening here, we're chucking away errors
          .sequence[JsResult, (ProductRatePlanChargeId, ProductRatePlanChargeProductType)]
          .map(_.toMap)
      case _ => JsError("No valid benefits found")
    }
  }

  private def productRatePlanReads(productType: Option[ProductType], pid: ProductId): Reads[ProductRatePlan] =
    (json: JsValue) => {
      ((__ \ "id").read[String].map(ProductRatePlanId) and
        (__ \ "name").read[String] and
        Reads.pure(pid) and
        (__ \ "productRatePlanCharges").read[Map[ProductRatePlanChargeId, ProductRatePlanChargeProductType]](productRatePlanChargesReads) and
        Reads.pure(productType))(ProductRatePlan.apply _).reads(json)
    }

  val productsReads: Reads[List[ProductRatePlan]] =
    (json: JsValue) =>
      json \ "products" match {
        case JsDefined(JsArray(products)) =>
          products.toList
            .map { product =>
              val productId = (product \ "id").as[String]
              val productType = (product \ "ProductType__c").asOpt[String].map(ProductType)
              val reads = productRatePlanReads(productType, ProductId(productId))
              (product \ "productRatePlans").validate[List[ProductRatePlan]](niceListReads(reads))
            }
            .filter(_.isSuccess)
            .sequence[JsResult, List[ProductRatePlan]]
            .map(_.flatten)
        case a => JsError(s"No product array found, got $a")
      }
}
