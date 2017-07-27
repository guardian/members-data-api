package components

import akka.actor.ActorSystem
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient

import scalaz.std.scalaFuture._
import com.gu.config
import com.gu.memsub.services.PaymentService
import com.gu.memsub.subsv2.services.SubscriptionService.CatalogMap
import com.gu.memsub.subsv2.services._
import com.gu.monitoring.ServiceMetrics
import com.gu.okhttp.RequestRunners
import com.gu.salesforce.SimpleContactRepository
import com.gu.stripe.StripeService
import com.gu.touchpoint.TouchpointBackendConfig
import com.gu.zuora.rest.SimpleClient
import com.gu.zuora.soap.ClientWithFeatureSupplier
import com.gu.zuora.{ZuoraRestService, ZuoraService, rest}
import configuration.Config
import prodtest.{Allocator, FeatureToggleDataUpdatedOnSchedule}
import org.joda.time.LocalDate
import services.IdentityService.IdentityConfig
import services._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class TouchpointComponents(stage: String)(implicit system: ActorSystem) {
  implicit val ec = system.dispatcher
  lazy val conf = Config.config.getConfig("touchpoint.backend")
  lazy val environmentConf = conf.getConfig(s"environments.$stage")

  lazy val identityConfig = new IdentityConfig {
    override def token: String = environmentConf.getString("identity.marketingToken")
    override def url: String = environmentConf.getString("identity.apiUrl")
  }

  lazy val identityService = new IdentityService(identityConfig, RequestRunners.futureRunner)
  lazy val digitalPackConf = environmentConf.getConfig(s"zuora.ratePlanIds.digitalpack")
  lazy val paperCatalogConf = environmentConf.getConfig(s"zuora.productIds.subscriptions")
  lazy val membershipConf = environmentConf.getConfig(s"zuora.ratePlanIds.membership")
  lazy val sfOrganisationId = environmentConf.getString("salesforce.organization-id")
  lazy val sfSecret = environmentConf.getString("salesforce.hook-secret")
  lazy val dynamoAttributesTable = environmentConf.getString("dynamodb.table")
  lazy val dynamoBehaviourTable = environmentConf.getString("behaviour.dynamodb.table")
  lazy val dynamoFeatureToggleTable = environmentConf.getString("featureToggles.dynamodb.table")
  lazy val giraffeSns = environmentConf.getString("giraffe.sns")

  lazy val digitalPackPlans = config.DigitalPackRatePlanIds.fromConfig(digitalPackConf)
  lazy val productIds = config.SubsV2ProductIds(environmentConf.getConfig("zuora.productIds"))
  lazy val membershipPlans = config.MembershipRatePlanIds.fromConfig(membershipConf)
  lazy val subsProducts = config.SubscriptionsProductIds(paperCatalogConf)

  lazy val tpConfig = TouchpointBackendConfig.byEnv(stage, conf)
  implicit lazy val _bt = tpConfig
  lazy val metrics = new ServiceMetrics(tpConfig.zuoraRest.envName, Config.applicationName,_: String)

  lazy val stripeService = new StripeService(tpConfig.stripe, RequestRunners.loggingRunner(metrics("stripe")))
  lazy val giraffeStripeService = new StripeService(tpConfig.giraffe, RequestRunners.loggingRunner(metrics("stripe")))
  lazy val soapClient = new ClientWithFeatureSupplier(Set.empty, tpConfig.zuoraSoap,
    RequestRunners.loggingRunner(metrics("zuora-soap")),
    RequestRunners.loggingRunner(metrics("zuora-soap"))
  )
  lazy val contactRepo = new SimpleContactRepository(tpConfig.salesforce, system.scheduler, Config.applicationName)
  lazy val attrService: AttributeService = new ScanamoAttributeService(new AmazonDynamoDBAsyncClient(com.gu.aws.CredentialsProvider).withRegion(Regions.EU_WEST_1), dynamoAttributesTable)
  lazy val behaviourService: BehaviourService = new ScanamoBehaviourService(new AmazonDynamoDBAsyncClient(com.gu.aws.CredentialsProvider).withRegion(Regions.EU_WEST_1), dynamoBehaviourTable)
  lazy val featureToggleService: ScanamoFeatureToggleService = new ScanamoFeatureToggleService(new AmazonDynamoDBAsyncClient(com.gu.aws.CredentialsProvider).withRegion(Regions.EU_WEST_1), dynamoFeatureToggleTable)
  lazy val snsGiraffeService: SNSGiraffeService = SNSGiraffeService(giraffeSns)
  lazy val zuoraService = new ZuoraService(soapClient)
  implicit lazy val simpleClient = new SimpleClient[Future](tpConfig.zuoraRest, RequestRunners.futureRunner)
  lazy val zuoraRestService = new ZuoraRestService[Future]()
  lazy val catalogService = new CatalogService[Future](productIds, simpleClient, Await.result(_, 10.seconds), stage)
  lazy val futureCatalog: Future[CatalogMap] = catalogService.catalog.map(_.fold[CatalogMap](error => {println(s"error: ${error.list.mkString}"); Map()}, _.map))

  lazy val subService = new SubscriptionService[Future](productIds, futureCatalog, simpleClient, zuoraService.getAccountIds)
  lazy val paymentService = new PaymentService(stripeService, zuoraService, catalogService.unsafeCatalog.productMap)
  lazy val scheduledUpdateVariables = new FeatureToggleDataUpdatedOnSchedule(featureToggleService)
  lazy val testAllocator = new Allocator
}
