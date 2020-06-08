package components

import akka.actor.ActorSystem
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder
import scalaz.std.scalaFuture._
import com.gu.config
import com.gu.i18n.Country
import com.gu.identity.IdapiService
import com.gu.memsub.services.PaymentService
import com.gu.memsub.subsv2.services.SubscriptionService.CatalogMap
import com.gu.memsub.subsv2.services._
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import com.gu.okhttp.RequestRunners
import com.gu.salesforce.SimpleContactRepository
import com.gu.stripe.StripeService
import com.gu.touchpoint.TouchpointBackendConfig
import com.gu.zuora.api.{InvoiceTemplate, InvoiceTemplates, PaymentGateway}
import com.gu.zuora.rest.SimpleClient
import com.gu.zuora.rest.ZuoraRestService
import com.gu.zuora.soap.ClientWithFeatureSupplier
import com.gu.zuora.ZuoraSoapService
import configuration.Config
import loghandling.ZuoraRequestCounter
import prodtest.FeatureToggleDataUpdatedOnSchedule
import services._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class TouchpointComponents(stage: String)(implicit  system: ActorSystem, executionContext: ExecutionContext) {
  implicit val ec = system.dispatcher
  lazy val conf = Config.config.getConfig("touchpoint.backend")
  lazy val environmentConf = conf.getConfig(s"environments.$stage")

  lazy val digitalPackConf = environmentConf.getConfig(s"zuora.ratePlanIds.digitalpack")
  lazy val paperCatalogConf = environmentConf.getConfig(s"zuora.productIds.subscriptions")
  lazy val membershipConf = environmentConf.getConfig(s"zuora.ratePlanIds.membership")
  lazy val dynamoAttributesTable = environmentConf.getString("dynamodb.table")
  lazy val dynamoFeatureToggleTable = environmentConf.getString("featureToggles.dynamodb.table")
  lazy val invoiceTemplatesConf = environmentConf.getConfig(s"zuora.invoiceTemplateIds")

  lazy val digitalPackPlans = config.DigitalPackRatePlanIds.fromConfig(digitalPackConf)
  lazy val productIds = config.SubsV2ProductIds(environmentConf.getConfig("zuora.productIds"))
  lazy val membershipPlans = config.MembershipRatePlanIds.fromConfig(membershipConf)
  lazy val subsProducts = config.SubscriptionsProductIds(paperCatalogConf)

  lazy val tpConfig = TouchpointBackendConfig.byEnv(stage, conf)
  implicit lazy val _bt = tpConfig

  lazy val ukStripeService = new StripeService(tpConfig.stripeUKMembership, RequestRunners.futureRunner)
  lazy val auStripeService = new StripeService(tpConfig.stripeAUMembership, RequestRunners.futureRunner)
  lazy val allStripeServices = Seq(ukStripeService, auStripeService)
  lazy val stripeServicesByPaymentGateway: Map[PaymentGateway, StripeService] = allStripeServices.map(s => s.paymentGateway -> s).toMap
  lazy val stripeServicesByPublicKey: Map[String, StripeService] = allStripeServices.map(s => s.publicKey -> s).toMap
  lazy val invoiceTemplateIdsByCountry: Map[Country, InvoiceTemplate] = InvoiceTemplates.fromConfig(invoiceTemplatesConf).map(it => (it.country, it)).toMap

  lazy val contactRepo: SimpleContactRepository = new SimpleContactRepository(tpConfig.salesforce, system.scheduler, Config.applicationName)
  lazy val salesforceService: SalesforceService = new SalesforceService(contactRepo)
  lazy val dynamoClientBuilder: AmazonDynamoDBAsyncClientBuilder = AmazonDynamoDBAsyncClientBuilder.standard().withCredentials(com.gu.aws.CredentialsProvider).withRegion(Regions.EU_WEST_1)
  lazy val attrService: AttributeService = new ScanamoAttributeService(dynamoClientBuilder.build(), dynamoAttributesTable)
  lazy val featureToggleService: FeatureToggleService = new ScanamoFeatureToggleService(dynamoClientBuilder.build(), dynamoFeatureToggleTable)

  private lazy val zuoraSoapClient = new ClientWithFeatureSupplier(Set.empty, tpConfig.zuoraSoap, RequestRunners.futureRunner, RequestRunners.futureRunner)
  lazy val zuoraService = new ZuoraSoapService(zuoraSoapClient)

  private lazy val zuoraRestClient = new SimpleClient[Future](tpConfig.zuoraRest, ZuoraRequestCounter.withZuoraRequestCounter(RequestRunners.configurableFutureRunner(60.seconds)))
  lazy val zuoraRestService = new ZuoraRestService[Future]()(futureInstance(ec), zuoraRestClient)

  lazy val catalogRestClient = new SimpleClient[Future](tpConfig.zuoraRest, RequestRunners.configurableFutureRunner(60.seconds))
  lazy val catalogService = new CatalogService[Future](productIds, FetchCatalog.fromZuoraApi(catalogRestClient), Await.result(_, 60.seconds), stage)

  lazy val futureCatalog: Future[CatalogMap] = catalogService.catalog
    .map(_.fold[CatalogMap](error => {println(s"error: ${error.list.toList.mkString}"); Map()}, _.map))
    .recover {
      case error =>
        SafeLogger.error(scrub"Failed to load the product catalog from Zuora due to: $error")
        throw error
    }

  lazy val subService = new SubscriptionService[Future](productIds, futureCatalog, zuoraRestClient, zuoraService.getAccountIds)
  lazy val paymentService = new PaymentService(zuoraService, catalogService.unsafeCatalog.productMap)
  lazy val featureToggleData = new FeatureToggleDataUpdatedOnSchedule(featureToggleService, stage)

  lazy val idapiService = new IdapiService(tpConfig.idapi, RequestRunners.futureRunner)
  lazy val identityAuthService = new IdentityAuthService(tpConfig.idapi)

}
