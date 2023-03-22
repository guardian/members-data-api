package components

import akka.actor.ActorSystem
import com.typesafe.config.Config
import configuration.Stage
import monitoring.CreateMetrics
import services.salesforce.ContactRepository
import services.stripe.BasicStripeService
import services.subscription.SubscriptionService
import services.zuora.rest.ZuoraRestService
import services.zuora.soap.ZuoraSoapService
import services.{CatalogService, HealthCheckableService, SupporterProductDataService}

import scala.concurrent.{ExecutionContext, Future}

class TouchpointBackends(
    actorSystem: ActorSystem,
    config: Config,
    createMetrics: CreateMetrics,
    supporterProductDataServiceOverride: Option[SupporterProductDataService] = None,
    contactRepositoryOverride: Option[ContactRepository] = None,
    subscriptionServiceOverride: Option[SubscriptionService] = None,
    zuoraRestServiceOverride: Option[ZuoraRestService] = None,
    catalogServiceOverride: Option[CatalogService] = None,
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
