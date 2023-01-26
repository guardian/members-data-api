package components

import akka.actor.ActorSystem
import com.gu.memsub.subsv2.services.{CatalogService, SubscriptionService}
import com.gu.zuora.ZuoraSoapService
import com.gu.zuora.rest.ZuoraRestService
import com.typesafe.config.Config
import configuration.Stage
import monitoring.CreateMetrics
import services.salesforce.ContactRepository
import services.{BasicStripeService, HealthCheckableService, SupporterProductDataService}

import scala.concurrent.{ExecutionContext, Future}

class TouchpointBackends(
    actorSystem: ActorSystem,
    config: Config,
    createMetrics: CreateMetrics,
    supporterProductDataServiceOverride: Option[SupporterProductDataService] = None,
    contactRepositoryOverride: Option[ContactRepository] = None,
    subscriptionServiceOverride: Option[SubscriptionService[Future]] = None,
    zuoraRestServiceOverride: Option[ZuoraRestService[Future]] = None,
    catalogServiceOverride: Option[CatalogService[Future]] = None,
    zuoraServiceOverride: Option[ZuoraSoapService with HealthCheckableService] = None,
    patronsStripeServiceOverride: Option[BasicStripeService] = None,
)(implicit
    executionContext: ExecutionContext,
) {
  private val defaultStage = Stage(config.getString("touchpoint.backend.default"))
  val normal = new TouchpointComponents(
    defaultStage,
    createMetrics,
    config,
    supporterProductDataServiceOverride,
    contactRepositoryOverride,
    subscriptionServiceOverride,
    zuoraRestServiceOverride,
    catalogServiceOverride,
    zuoraServiceOverride,
    patronsStripeServiceOverride,
  )(
    actorSystem,
    executionContext,
  )
  private val testStage = Stage(config.getString("touchpoint.backend.test"))
  val test =
    new TouchpointComponents(
      testStage,
      createMetrics,
      config,
      supporterProductDataServiceOverride,
      contactRepositoryOverride,
      subscriptionServiceOverride,
      zuoraRestServiceOverride,
      catalogServiceOverride,
      zuoraServiceOverride,
    )(actorSystem, executionContext)
}
