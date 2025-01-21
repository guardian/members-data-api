package com.gu.memsub.subsv2.services

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.GetObjectRequest
import com.gu.aws.AwsS3
import com.gu.config.SubsV2ProductIds
import com.gu.config.SubsV2ProductIds.ProductMap
import com.gu.memsub.ProductRatePlanChargeProductType
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, ProductRatePlanId}
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.CatJsonReads._
import Catalog.ProductRatePlanMap
import com.gu.monitoring.SafeLogger._
import com.gu.monitoring.SafeLogging
import com.gu.zuora.rest.SimpleClient
import play.api.libs.json.{Reads => JsReads, _}
import scalaz.syntax.functor.ToFunctorOps
import scalaz.syntax.monad._
import scalaz.syntax.std.either._
import scalaz.{Functor, Monad, \/}

import scala.concurrent.Future

object FetchCatalog {

  def fromZuoraApi[M[_]: Monad](httpClient: SimpleClient[M])(implicit logPrefix: LogPrefix): M[String \/ JsValue] =
    httpClient.get[JsValue]("catalog/products?pageSize=40")(
      new JsReads[JsValue] {
        override def reads(json: JsValue): JsResult[JsValue] = JsSuccess(json)
      },
      logPrefix,
    )

  def fromS3(zuoraEnvironment: String, s3Client: AmazonS3 = AwsS3.client)(implicit m: Monad[Future]): Future[String \/ JsValue] = {
    val catalogRequest = new GetObjectRequest(s"gu-zuora-catalog/PROD/Zuora-${zuoraEnvironment}", "catalog.json")
    AwsS3.fetchJson(s3Client, catalogRequest)(LogPrefix.noLogPrefix).point[Future]
  }

}

object CatalogService extends SafeLogging {

  def read[M[_]: Functor](fetchCatalog: M[String \/ JsValue], products: ProductMap): M[String \/ Catalog] =
    for {
      failableJsCatalog <- fetchCatalog
    } yield {

      val failableCatalog = for {
        catalog <- failableJsCatalog
        plans <- Json.fromJson[List[ProductRatePlan]](catalog)(productsReads).asEither.toDisjunction.leftMap(_.toString)
      } yield plans.map(catalogZuoraPlan => catalogZuoraPlan.id -> catalogZuoraPlan).toMap

      failableCatalog.leftMap(error => logger.errorNoPrefix(scrub"Failed to load catalog: $error"))

      failableCatalog.map(catalogMap => Catalog(catalogMap ++ patronPlans, products))
    }

  private val patronPlans = Map[ProductRatePlanId, ProductRatePlan](
    Catalog.guardianPatronProductRatePlanId -> ProductRatePlan(
      Catalog.guardianPatronProductRatePlanId,
      "Guardian Patron", // was subscription.plan.id but isn't used for patron
      SubsV2ProductIds.guardianPatronProductId,
      Map[ProductRatePlanChargeId, ProductRatePlanChargeProductType](
        Catalog.guardianPatronProductRatePlanChargeId -> ProductRatePlanChargeProductType.GuardianPatron,
      ),
      Some(ProductType("Membership")), // not used for patron - only used for payment related emails
    ),
  )

}
