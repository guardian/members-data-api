package com.gu.memsub.subsv2.services

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.GetObjectRequest
import com.gu.aws.AwsS3
import com.gu.config.SubsV2ProductIds
import com.gu.config.SubsV2ProductIds.ProductIds
import com.gu.memsub.Benefit
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, ProductRatePlanId}
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.CatJsonReads._
import Catalog.CatalogMap
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

  def read[M[_]: Functor](fetchCatalog: M[String \/ JsValue], productIds: ProductIds): M[Catalog] =
    for {
      failableJsCatalog <- fetchCatalog
    } yield {

      val failableCatalog = for {
        catalog <- failableJsCatalog
        plans <- Json.fromJson[List[CatalogZuoraPlan]](catalog)(catalogZuoraPlanListReads).asEither.toDisjunction.leftMap(_.toString)
      } yield plans.map(catalogZuoraPlan => catalogZuoraPlan.id -> catalogZuoraPlan).toMap

      val catalogMap = failableCatalog
        .leftMap[CatalogMap](error => {
          logger.errorNoPrefix(scrub"error: $error"); Map.empty // not sure why we empty-on-failure
        })
        .merge

      Catalog(catalogMap ++ patronPlans, productIds)
    }

  private val patronPlans = Map[ProductRatePlanId, CatalogZuoraPlan](
    Catalog.guardianPatronProductRatePlanId -> CatalogZuoraPlan(
      Catalog.guardianPatronProductRatePlanId,
      "Guardian Patron", // was subscription.plan.id but isn't used for patron
      SubsV2ProductIds.guardianPatronProductId,
      Map[ProductRatePlanChargeId, Benefit](Catalog.guardianPatronProductRatePlanChargeId -> Benefit.GuardianPatron),
      Some(ProductType("Membership")), // not used for patron - only used for payment related emails
    ),
  )

}
