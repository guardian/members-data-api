package components

import com.gu.memsub.subsv2.services.SubscriptionService.CatalogMap
import com.gu.memsub.subsv2.services.{CatalogService, SubscriptionService}
import com.gu.zuora.ZuoraSoapService
import com.typesafe.config.Config
import configuration.Stage
import monitoring.CreateMetrics
import org.apache.pekko.actor.ActorSystem
import services.salesforce.ContactRepository
import services.stripe.{BasicStripeService, ChooseStripe}
import services.zuora.rest.ZuoraRestService
import services.{HealthCheckableService, SupporterProductDataService}

import scala.concurrent.{ExecutionContext, Future}

class TouchpointBackends(
    actorSystem: ActorSystem,
    config: Config,
    createMetrics: CreateMetrics,
    supporterProductDataServiceOverride: Option[SupporterProductDataService] = None,
    contactRepositoryOverride: Option[ContactRepository] = None,
    subscriptionServiceOverride: Option[SubscriptionService[Future]] = None,
    zuoraRestServiceOverride: Option[ZuoraRestService] = None,
    catalogServiceOverride: Option[Future[CatalogMap]] = None,
    zuoraServiceOverride: Option[ZuoraSoapService with HealthCheckableService] = None,
    patronsStripeServiceOverride: Option[BasicStripeService] = None,
    chooseStripeOverride: Option[ChooseStripe] = None,
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
    chooseStripeOverride,
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
      patronsStripeServiceOverride,
      chooseStripeOverride,
    )(actorSystem, executionContext)
}
