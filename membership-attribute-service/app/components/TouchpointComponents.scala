package components

import akka.actor.ActorSystem
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.gu.config
import com.gu.memsub.{Digipack, Membership, ProductFamily, Subscriptions}
import com.gu.memsub.services.{CatalogService, PaymentService, PromoService, SubscriptionService}
import com.gu.monitoring.ServiceMetrics
import com.gu.salesforce.SimpleContactRepository
import com.gu.stripe.StripeService
import com.gu.touchpoint.TouchpointBackendConfig
import com.gu.zuora.soap.ClientWithFeatureSupplier
import com.gu.zuora.{ZuoraService, rest}
import com.squareup.okhttp.OkHttpClient
import configuration.Config
import services.{AttributeService, ScanamoAttributeService}

class TouchpointComponents(stage: String)(implicit system: ActorSystem) {
  implicit val ec = system.dispatcher
  lazy val conf = Config.config.getConfig("touchpoint.backend")
  lazy val environmentConf = conf.getConfig(s"environments.$stage")

  lazy val digitalPackConf = environmentConf.getConfig(s"zuora.ratePlanIds.digitalpack")
  lazy val paperCatalogConf = environmentConf.getConfig(s"zuora.productIds.subscriptions")
  lazy val membershipConf = environmentConf.getConfig(s"zuora.ratePlanIds.membership")
  lazy val sfOrganisationId = environmentConf.getString("salesforce.organization-id")
  lazy val sfSecret = environmentConf.getString("salesforce.hook-secret")
  lazy val dynamoTable = environmentConf.getString("dynamodb.table")

  lazy val digitalPackPlans = config.DigitalPackRatePlanIds.fromConfig(digitalPackConf)
  lazy val membershipPlans = config.MembershipRatePlanIds.fromConfig(membershipConf)
  lazy val subsProducts = config.SubscriptionsProductIds(paperCatalogConf)

  lazy val tpConfig = TouchpointBackendConfig.byEnv(stage, conf)
  implicit lazy val _bt = tpConfig
  lazy val metrics = new ServiceMetrics(tpConfig.zuoraRest.envName, Config.applicationName,_: String)

  lazy val stripeService = new StripeService(tpConfig.stripe, metrics("stripe"))
  lazy val soapClient = new ClientWithFeatureSupplier(Set.empty, tpConfig.zuoraSoap, metrics("zuora-soap"))
  lazy val restClient = new rest.Client(tpConfig.zuoraRest, metrics("zuora-rest"))
  lazy val contactRepo = new SimpleContactRepository(tpConfig.salesforce, system.scheduler, Config.applicationName)
  lazy val attrService: AttributeService = new ScanamoAttributeService(new AmazonDynamoDBAsyncClient().withRegion(Regions.EU_WEST_1), dynamoTable)
  lazy val zuoraService = new ZuoraService(soapClient, restClient)
  lazy val catalogService = CatalogService(restClient, subsProducts, membershipPlans, digitalPackPlans, stage)
  lazy val digipackSubscriptionService = new SubscriptionService(zuoraService, stripeService, catalogService.digipackCatalog)
  lazy val membershipSubscriptionService = new SubscriptionService(zuoraService, stripeService, catalogService.membershipCatalog)

  def subService(implicit pf: ProductFamily) = pf match {
    case Membership => membershipSubscriptionService
    case Subscriptions => digipackSubscriptionService
  }

  lazy val paymentService = new PaymentService(stripeService, zuoraService, catalogService)
}
