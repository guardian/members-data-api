package components

import com.gu.membership.salesforce.SimpleContactRepository
import com.gu.membership.stripe.StripeService
import com.gu.membership.touchpoint.TouchpointBackendConfig
import com.gu.membership.zuora.{SubscriptionService, rest, soap}
import com.gu.monitoring.ServiceMetrics
import com.gu.services.{ZuoraPaymentService, PaymentService}
import configuration.Config.SalesforceConfig
import play.api.BuiltInComponents
import play.api.libs.ws.ning.NingWSComponents
import repositories.MembershipAttributesSerializer
import play.api.libs.concurrent.Execution.Implicits._
import services.DynamoAttributeService
import com.softwaremill.macwire._

trait TouchpointComponents extends ConfigComponents{ self: BuiltInComponents with NingWSComponents =>
  val stage: String
  val sfConfig: SalesforceConfig
  lazy val conf = config.config.getConfig("touchpoint.backend")
  lazy val tpConfig = TouchpointBackendConfig.byEnv(stage, conf)
  lazy val metrics = new ServiceMetrics(tpConfig.zuoraRest.envName, config.applicationName,_: String)
  lazy val soapClient = new soap.Client(tpConfig.zuoraSoap, metrics("zuora-soap"), actorSystem)
  lazy val restClient = new rest.Client(tpConfig.zuoraRest, metrics("zuora-rest"))
  lazy val contactRepo = new SimpleContactRepository(tpConfig.salesforce, actorSystem.scheduler, config.applicationName)
  lazy val stripeService = new StripeService(tpConfig.stripe, metrics("stripe"), wsClient)
  lazy val subService = wire[SubscriptionService]
  lazy val paymentService = wire[ZuoraPaymentService]
  lazy val attrService = DynamoAttributeService(MembershipAttributesSerializer(conf.getString(s"environments.$stage.dynamodb.table")))
}
