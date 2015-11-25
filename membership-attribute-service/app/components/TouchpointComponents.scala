package components

import com.gu.config.{Membership, DigitalPack}
import com.gu.membership.salesforce.SimpleContactRepository
import com.gu.membership.stripe.StripeService
import com.gu.membership.touchpoint.TouchpointBackendConfig
import com.gu.membership.zuora.{SubscriptionService, rest, soap}
import com.gu.monitoring.ServiceMetrics
import com.gu.services.ZuoraPaymentService
import configuration.Config
import play.api.libs.concurrent.Akka
import play.api.libs.ws.WS
import repositories.MembershipAttributesSerializer
import play.api.Play.current
import services.{AttributeService, DynamoAttributeService}
import play.api.libs.concurrent.Execution.Implicits._

case class TouchpointComponents(stage: String) {
  lazy val conf = Config.config.getConfig("touchpoint.backend")
  lazy val environmentConf = conf.getConfig(s"environments.$stage");

  lazy val digitalPackConf = environmentConf.getConfig(s"zuora.ratePlanIds")
  lazy val membershipConf = environmentConf.getConfig(s"zuora.ratePlanIds.membership")
  lazy val sfOrganisationId = environmentConf.getString("salesforce.organization-id")
  lazy val sfSecret = environmentConf.getString("salesforce.hook-secret")
  lazy val dynamoTable = environmentConf.getString("dynamodb.table")

  lazy val digitalPackPlans = DigitalPack.fromConfig(digitalPackConf)
  lazy val membershipPlans = Membership.fromConfig(membershipConf)

  lazy val tpConfig = TouchpointBackendConfig.byEnv(stage, conf)
  lazy val metrics = new ServiceMetrics(tpConfig.zuoraRest.envName, Config.applicationName,_: String)

  lazy val stripeService = new StripeService(tpConfig.stripe, metrics("stripe"), WS.client)
  lazy val soapClient = new soap.Client(tpConfig.zuoraSoap, metrics("zuora-soap"), Akka.system)
  lazy val restClient = new rest.Client(tpConfig.zuoraRest, metrics("zuora-rest"))

  lazy val contactRepo = new SimpleContactRepository(tpConfig.salesforce, Akka.system.scheduler, Config.applicationName)
  lazy val attrService: AttributeService = DynamoAttributeService(MembershipAttributesSerializer(dynamoTable))
  lazy val subService = new SubscriptionService(soapClient, restClient)
  lazy val paymentService = new ZuoraPaymentService(stripeService, subService)
}
