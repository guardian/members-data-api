package components

import com.gu.aws.ProfileName
import com.gu.config
import com.gu.identity.IdapiService
import com.gu.identity.auth._
import com.gu.identity.play.IdentityPlayAuthService
import com.gu.memsub.subsv2.services.SubscriptionService.CatalogMap
import com.gu.monitoring.{SafeLogging, ZuoraMetrics}
import com.gu.okhttp.RequestRunners
import com.gu.touchpoint.TouchpointBackendConfig
import com.gu.zuora.rest
import com.gu.zuora.soap.ClientWithFeatureSupplier
import com.typesafe.config.Config
import configuration.OptionalConfig._
import configuration.Stage
import monitoring.{CreateMetrics, CreateNoopMetrics}
import org.apache.pekko.actor.ActorSystem
import org.http4s.Uri
import scalaz.std.scalaFuture._
import services._
import services.salesforce.{ContactRepository, ContactRepositoryWithMetrics, CreateScalaforce, SimpleContactRepository}
import services.stripe.{BasicStripeService, BasicStripeServiceWithMetrics, ChooseStripe, HttpBasicStripeService}
import services.subscription.{CancelSubscription, SubscriptionService, SubscriptionServiceWithMetrics, ZuoraSubscriptionService}
import services.zuora.payment.{SetPaymentCard, ZuoraPaymentService}
import services.zuora.rest.{SimpleClient, SimpleClientZuoraRestService, ZuoraRestService, ZuoraRestServiceWithMetrics}
import services.zuora.soap.{SimpleZuoraSoapService, ZuoraSoapService, ZuoraSoapServiceWithMetrics}
import software.amazon.awssdk.auth.credentials.{
  AwsCredentialsProviderChain,
  EnvironmentVariableCredentialsProvider,
  InstanceProfileCredentialsProvider,
  ProfileCredentialsProvider,
}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.{DynamoDbAsyncClient, DynamoDbAsyncClientBuilder}

import java.util.concurrent.TimeUnit.SECONDS
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class TouchpointComponents(
    stage: Stage,
    createMetrics: CreateMetrics,
    conf: Config,
    supporterProductDataServiceOverride: Option[SupporterProductDataService] = None,
    contactRepositoryOverride: Option[ContactRepository] = None,
    subscriptionServiceOverride: Option[SubscriptionService] = None,
    zuoraRestServiceOverride: Option[ZuoraRestService] = None,
    catalogServiceOverride: Option[CatalogService] = None,
    zuoraServiceOverride: Option[ZuoraSoapService with HealthCheckableService] = None,
    patronsStripeServiceOverride: Option[BasicStripeService] = None,
    chooseStripeOverride: Option[ChooseStripe] = None,
)(implicit
    system: ActorSystem,
    executionContext: ExecutionContext,
) extends SafeLogging {
  lazy val touchpointConfig = conf.getConfig("touchpoint.backend")
  lazy val environmentConfig = touchpointConfig.getConfig(s"environments." + stage.value)

  lazy val digitalPackConf = environmentConfig.getConfig(s"zuora.ratePlanIds.digitalpack")
  lazy val paperCatalogConf = environmentConfig.getConfig(s"zuora.productIds.subscriptions")
  lazy val membershipConf = environmentConfig.getConfig(s"zuora.ratePlanIds.membership")
  lazy val supporterProductDataTable = environmentConfig.getString("supporter-product-data.table")
  lazy val invoiceTemplatesConf = environmentConfig.getConfig(s"zuora.invoiceTemplateIds")

  lazy val digitalPackPlans = config.DigitalPackRatePlanIds.fromConfig(digitalPackConf)
  lazy val productIds = config.SubsV2ProductIds(environmentConfig.getConfig("zuora.productIds"))
  lazy val membershipPlans = config.MembershipRatePlanIds.fromConfig(membershipConf)
  lazy val subsProducts = config.SubscriptionsProductIds(paperCatalogConf)

  lazy val backendConfig = TouchpointBackendConfig.byEnv(stage.value, touchpointConfig)

  lazy val useFineMetrics = conf.optionalBoolean("use-fine-metrics", false)
  lazy val createFineMetrics: CreateMetrics = if (useFineMetrics) createMetrics else CreateNoopMetrics

  lazy val patronsStripeService: BasicStripeService = {
    lazy val patronsBasicHttpStripeService = new HttpBasicStripeService(backendConfig.stripePatrons, RequestRunners.futureRunner)
    patronsStripeServiceOverride
      .getOrElse(new BasicStripeServiceWithMetrics(patronsBasicHttpStripeService, createFineMetrics))
  }

  lazy val salesforce = CreateScalaforce(backendConfig.salesforce, system.scheduler, configuration.ApplicationName.applicationName)

  lazy val contactRepository: ContactRepository = {
    lazy val simpleContactRepository = new SimpleContactRepository(salesforce)
    lazy val contactRepositoryWithMetrics = new ContactRepositoryWithMetrics(simpleContactRepository, createFineMetrics)

    contactRepositoryOverride.getOrElse(contactRepositoryWithMetrics)
  }
  lazy val salesforceService: SalesforceService = new SalesforceService(salesforce)

  lazy val CredentialsProvider = AwsCredentialsProviderChain.builder
    .credentialsProviders(
      ProfileCredentialsProvider.builder.profileName(ProfileName).build,
      InstanceProfileCredentialsProvider.builder.asyncCredentialUpdateEnabled(false).build,
      EnvironmentVariableCredentialsProvider.create(),
    )
    .build

  private lazy val dynamoClientBuilder: DynamoDbAsyncClientBuilder = DynamoDbAsyncClient.builder
    .credentialsProvider(CredentialsProvider)
    .region(Region.EU_WEST_1)

  private lazy val mapper = new SupporterRatePlanToAttributesMapper(stage)
  private lazy val dynamoSupporterProductDataService =
    new DynamoSupporterProductDataService(dynamoClientBuilder.build(), supporterProductDataTable, mapper, createMetrics)

  lazy val supporterProductDataService: SupporterProductDataService =
    supporterProductDataServiceOverride.getOrElse(dynamoSupporterProductDataService)

  private val zuoraMetrics = new ZuoraMetrics(stage.value, configuration.ApplicationName.applicationName)

  lazy val zuoraSoapService = {
    lazy val zuoraSoapClient =
      new ClientWithFeatureSupplier(
        featureCodes = Set.empty,
        apiConfig = backendConfig.zuoraSoap,
        httpClient = RequestRunners.configurableFutureRunner(timeout = Duration(30, SECONDS)),
        extendedHttpClient = RequestRunners.futureRunner,
        metrics = zuoraMetrics,
      )

    lazy val simpleZuoraSoapService = new SimpleZuoraSoapService(zuoraSoapClient)

    zuoraServiceOverride.getOrElse(
      new ZuoraSoapServiceWithMetrics(simpleZuoraSoapService, createFineMetrics) with HealthCheckableService {
        override def checkHealth: Boolean = zuoraSoapClient.isReady
      },
    )
  }

  lazy val zuoraRestClient = SimpleClient(backendConfig.zuoraRest, RequestRunners.configurableFutureRunner(30.seconds))

  lazy val zuoraRestService: ZuoraRestService = {
    lazy val simpleClientZuoraRestService = new SimpleClientZuoraRestService(zuoraRestClient)
    zuoraRestServiceOverride.getOrElse(
      new ZuoraRestServiceWithMetrics(simpleClientZuoraRestService, createFineMetrics)(executionContext),
    )
  }

  lazy val catalogRestClient = rest.SimpleClient[Future](backendConfig.zuoraRest, RequestRunners.configurableFutureRunner(60.seconds))
  lazy val catalogService = catalogServiceOverride.getOrElse(
    new CatalogService(productIds, FetchCatalog.fromZuoraApi(catalogRestClient), Await.result(_, 60.seconds), stage.value),
  )

  lazy val futureCatalog: Future[CatalogMap] = catalogService.catalog
    .map(_.fold[CatalogMap](error => { println(s"error: ${error.list.toList.mkString}"); Map() }, _.map))
    .recover { case error =>
      logger.error(scrub"Failed to load the product catalog from Zuora due to: $error")
      throw error
    }

  lazy val subscriptionService: SubscriptionService = {
    lazy val zuoraSubscriptionService = new ZuoraSubscriptionService(productIds, futureCatalog, zuoraRestClient, zuoraSoapService.getAccountIds)

    subscriptionServiceOverride.getOrElse(
      new SubscriptionServiceWithMetrics(zuoraSubscriptionService, createFineMetrics),
    )
  }
  lazy val paymentService: ZuoraPaymentService = new ZuoraPaymentService(zuoraSoapService, catalogService.unsafeCatalog.productMap)

  lazy val idapiService = new IdapiService(backendConfig.idapi, RequestRunners.futureRunner)
  lazy val tokenVerifierConfig = OktaTokenValidationConfig(
    issuerUrl = OktaIssuerUrl(conf.getString("okta.verifier.issuerUrl")),
    audience = Some(OktaAudience(conf.getString("okta.verifier.audience"))),
    clientId = None,
  )
  lazy val identityPlayAuthService: IdentityPlayAuthService = {
    val apiConfig = backendConfig.idapi
    val idApiUrl = Uri.unsafeFromString(apiConfig.url)
    val idapiConfig = IdapiAuthConfig(idApiUrl, apiConfig.token, Some("membership"))
    IdentityPlayAuthService.unsafeInit(
      idapiConfig,
      tokenVerifierConfig,
    )
  }
  lazy val identityAuthService = new services.IdentityAuthService(identityPlayAuthService)

  lazy val guardianPatronService =
    new GuardianPatronService(
      supporterProductDataService,
      patronsStripeService,
      backendConfig.stripePatrons.stripeCredentials.publicKey,
      createMetrics,
    )

  lazy val chooseStripe: ChooseStripe = chooseStripeOverride.getOrElse(
    ChooseStripe.createFor(backendConfig.stripeUKMembership, backendConfig.stripeAUMembership, createMetrics),
  )

  lazy val paymentDetailsForSubscription: PaymentDetailsForSubscription = new PaymentDetailsForSubscription(paymentService)

  lazy val accountDetailsFromZuora: AccountDetailsFromZuora =
    new AccountDetailsFromZuora(
      createMetrics,
      zuoraRestService,
      contactRepository,
      subscriptionService,
      chooseStripe,
      paymentDetailsForSubscription,
    )

  def setPaymentCard(stripePublicKey: String): SetPaymentCard = {
    val stripeService = chooseStripe.serviceForPublicKey(stripePublicKey).toRight(s"No Stripe service for public key: $stripePublicKey")
    new SetPaymentCard(paymentService, zuoraSoapService, stripeService)
  }

  lazy val cancelSubscription = new CancelSubscription(subscriptionService, zuoraRestService)
}
