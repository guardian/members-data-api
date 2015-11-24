package components

import com.gu.config.{Membership, DigitalPack}
import com.gu.membership.salesforce.SimpleContactRepository
import com.gu.membership.stripe.StripeService
import com.gu.membership.touchpoint.TouchpointBackendConfig
import com.gu.membership.zuora.{SubscriptionService, rest, soap}
import com.gu.monitoring.ServiceMetrics
import com.gu.services.ZuoraPaymentService
import configuration.Config
import configuration.Config.SalesforceConfig
import framework.AllComponentTraits
import repositories.MembershipAttributesSerializer
import play.api.libs.concurrent.Execution.Implicits._

import services.DynamoAttributeService
import com.softwaremill.macwire._

trait TouchpointComponents { self: AllComponentTraits =>

  val stage: String
  val sfConfig: SalesforceConfig
  lazy val config: Config.type = Config

  lazy val conf = Config.config.getConfig("touchpoint.backend")
  lazy val digitalPackConf = conf.getConfig(s"environments.$stage.zuora.ratePlanIds")
  lazy val membershipConf = conf.getConfig(s"environments.$stage.zuora.ratePlanIds.membership")
  lazy val dynamoTable = conf.getString(s"environments.$stage.dynamodb.table")

  lazy val digitalPackPlans = DigitalPack.fromConfig(digitalPackConf)
  lazy val membershipPlans = Membership.fromConfig(membershipConf)

  lazy val tpConfig = TouchpointBackendConfig.byEnv(stage, conf)
  lazy val metrics = new ServiceMetrics(tpConfig.zuoraRest.envName, Config.applicationName,_: String)

  lazy val stripeService = new StripeService(tpConfig.stripe, metrics("stripe"), wsClient)
  lazy val soapClient = new soap.Client(tpConfig.zuoraSoap, metrics("zuora-soap"), actorSystem)
  lazy val restClient = new rest.Client(tpConfig.zuoraRest, metrics("zuora-rest"))

  lazy val contactRepo = new SimpleContactRepository(tpConfig.salesforce, actorSystem.scheduler, Config.applicationName)
  lazy val attrService = DynamoAttributeService(MembershipAttributesSerializer(dynamoTable))
  lazy val paymentService = wire[ZuoraPaymentService]
  lazy val subService = wire[SubscriptionService]
}
