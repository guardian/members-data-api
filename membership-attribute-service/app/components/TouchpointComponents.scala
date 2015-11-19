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
import play.api.libs.concurrent.Akka
import play.api.libs.ws.WS
import repositories.MembershipAttributesSerializer
import play.api.Play.current
import services.{AttributeService, DynamoAttributeService}
import play.api.libs.concurrent.Execution.Implicits._

trait TouchpointComponents {

  val stage: String
  val sfConfig: SalesforceConfig

  val conf = Config.config.getConfig("touchpoint.backend")
  val digitalPackConf = conf.getConfig(s"environments.$stage.zuora.ratePlanIds")
  val membershipConf = conf.getConfig(s"environments.$stage.zuora.ratePlanIds.membership")
  val dynamoTable = conf.getString(s"environments.$stage.dynamodb.table")

  val digitalPackPlans = DigitalPack.fromConfig(digitalPackConf)
  val membershipPlans = Membership.fromConfig(membershipConf)

  val tpConfig = TouchpointBackendConfig.byEnv(stage, conf)
  val metrics = new ServiceMetrics(tpConfig.zuoraRest.envName, Config.applicationName,_: String)

  val stripeService = new StripeService(tpConfig.stripe, metrics("stripe"), WS.client)
  val soapClient = new soap.Client(tpConfig.zuoraSoap, metrics("zuora-soap"), Akka.system)
  val restClient = new rest.Client(tpConfig.zuoraRest, metrics("zuora-rest"))

  val contactRepo = new SimpleContactRepository(tpConfig.salesforce, Akka.system.scheduler, Config.applicationName)
  val attrService: AttributeService = DynamoAttributeService(MembershipAttributesSerializer(dynamoTable))
  val subService = new SubscriptionService(soapClient, restClient)
  val paymentService = new ZuoraPaymentService(stripeService, subService)
}
