package components

import akka.actor.ActorSystem
import com.gu.aws.ProfileName
import com.gu.identity.IdapiService
import com.gu.identity.auth.{DefaultIdentityClaims, IdapiAuthConfig, OktaTokenValidationConfig}
import com.gu.identity.play.IdentityPlayAuthService
import com.gu.monitoring.ZuoraMetrics
import com.gu.okhttp.RequestRunners
import com.gu.touchpoint.TouchpointBackendConfig
import com.gu.zuora.rest
import com.typesafe.config.Config
import configuration.{DigitalPackRatePlanIds, MembershipRatePlanIds, Stage, SubsV2ProductIds, SubscriptionsProductIds}
import models.subscription.subsv2.services.SubscriptionService.CatalogMap
import models.subscription.subsv2.services.{CatalogService, FetchCatalog}
import models.{UserFromToken, UserFromTokenParser}
import monitoring.{CreateMetrics, SafeLogger}
import monitoring.SafeLogger._
import org.http4s.Uri
import scalaz.std.scalaFuture._
import services._
import services.salesforce.{ContactRepository, ContactRepositoryWithMetrics, CreateScalaforce, SimpleContactRepository}
import services.stripe.{BasicStripeService, BasicStripeServiceWithMetrics, ChooseStripe, HttpBasicStripeService}
import services.subscription.{SubscriptionService, SubscriptionServiceWithMetrics, ZuoraSubscriptionService}
import services.zuora.rest.{SimpleClient, SimpleClientZuoraRestService, ZuoraRestService, ZuoraRestServiceWithMetrics}
import services.zuora.soap.{ClientWithFeatureSupplier, SimpleZuoraSoapService, ZuoraSoapService, ZuoraSoapServiceWithMetrics}
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
    catalogServiceOverride: Option[CatalogService[Future]] = None,
    zuoraServiceOverride: Option[ZuoraSoapService with HealthCheckableService] = None,
    patronsStripeServiceOverride: Option[BasicStripeService] = None,
)(implicit
    system: ActorSystem,
    executionContext: ExecutionContext,
) {
  lazy val touchpointConfig = conf.getConfig("touchpoint.backend")
  lazy val environmentConfig = touchpointConfig.getConfig(s"environments." + stage.value)

  lazy val digitalPackConf = environmentConfig.getConfig(s"zuora.ratePlanIds.digitalpack")
  lazy val paperCatalogConf = environmentConfig.getConfig(s"zuora.productIds.subscriptions")
  lazy val membershipConf = environmentConfig.getConfig(s"zuora.ratePlanIds.membership")
  lazy val supporterProductDataTable = environmentConfig.getString("supporter-product-data.table")
  lazy val invoiceTemplatesConf = environmentConfig.getConfig(s"zuora.invoiceTemplateIds")

  lazy val digitalPackPlans = DigitalPackRatePlanIds.fromConfig(digitalPackConf)
  lazy val productIds = SubsV2ProductIds(environmentConfig.getConfig("zuora.productIds"))
  lazy val membershipPlans = MembershipRatePlanIds.fromConfig(membershipConf)
  lazy val subsProducts = SubscriptionsProductIds(paperCatalogConf)

  lazy val backendConfig = TouchpointBackendConfig.byEnv(stage.value, touchpointConfig)

  private lazy val patronsBasicHttpStripeService = new HttpBasicStripeService(backendConfig.stripePatrons, RequestRunners.futureRunner)
  lazy val patronsStripeService: BasicStripeService = patronsStripeServiceOverride
    .getOrElse(new BasicStripeServiceWithMetrics(patronsBasicHttpStripeService, createMetrics))

  private lazy val salesforce = CreateScalaforce(backendConfig.salesforce, system.scheduler, configuration.ApplicationName.applicationName)
  private lazy val simpleContactRepository = new SimpleContactRepository(salesforce)
  private lazy val contactRepositoryWithMetrics = new ContactRepositoryWithMetrics(simpleContactRepository, createMetrics)
  lazy val contactRepository: ContactRepository =
    contactRepositoryOverride.getOrElse(contactRepositoryWithMetrics)
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
  private lazy val zuoraSoapClient =
    new ClientWithFeatureSupplier(
      featureCodes = Set.empty,
      apiConfig = backendConfig.zuoraSoap,
      httpClient = RequestRunners.configurableFutureRunner(timeout = Duration(30, SECONDS)),
      extendedHttpClient = RequestRunners.futureRunner,
      metrics = zuoraMetrics,
    )

  private lazy val zuoraSoapService = new SimpleZuoraSoapService(zuoraSoapClient)

  lazy val zuoraService = zuoraServiceOverride.getOrElse(
    new ZuoraSoapServiceWithMetrics(zuoraSoapService, createMetrics) with HealthCheckableService {
      override def checkHealth: Boolean = zuoraSoapClient.isReady
    },
  )

  private lazy val zuoraRestClient = SimpleClient(backendConfig.zuoraRest, RequestRunners.configurableFutureRunner(30.seconds))
  private lazy val simpleClientZuoraRestService = new SimpleClientZuoraRestService(zuoraRestClient)
  lazy val zuoraRestService: ZuoraRestService = zuoraRestServiceOverride.getOrElse(
    new ZuoraRestServiceWithMetrics(simpleClientZuoraRestService, createMetrics)(executionContext),
  )

  lazy val catalogRestClient = rest.SimpleClient[Future](backendConfig.zuoraRest, RequestRunners.configurableFutureRunner(60.seconds))
  lazy val catalogService = catalogServiceOverride.getOrElse(
    new CatalogService[Future](productIds, FetchCatalog.fromZuoraApi(catalogRestClient), Await.result(_, 60.seconds), stage.value),
  )

  lazy val futureCatalog: Future[CatalogMap] = catalogService.catalog
    .map(_.fold[CatalogMap](error => { println(s"error: ${error.list.toList.mkString}"); Map() }, _.map))
    .recover { case error =>
      SafeLogger.error(scrub"Failed to load the product catalog from Zuora due to: $error")
      throw error
    }

  private lazy val zuoraSubscriptionService = new ZuoraSubscriptionService(productIds, futureCatalog, zuoraRestClient, zuoraService.getAccountIds)

  lazy val subscriptionService: SubscriptionService = subscriptionServiceOverride.getOrElse(
    new SubscriptionServiceWithMetrics(zuoraSubscriptionService, createMetrics),
  )
  lazy val paymentService: PaymentService = new PaymentService(zuoraService, catalogService.unsafeCatalog.productMap)

  lazy val idapiService = new IdapiService(backendConfig.idapi, RequestRunners.futureRunner)
  lazy val tokenVerifierConfig = OktaTokenValidationConfig(
    issuerUrl = conf.getString("okta.verifier.issuerUrl"),
    audience = conf.getString("okta.verifier.audience"),
  )
  lazy val identityPlayAuthService: IdentityPlayAuthService[UserFromToken, DefaultIdentityClaims] = {
    val apiConfig = backendConfig.idapi
    val idApiUrl = Uri.unsafeFromString(apiConfig.url)
    val idapiConfig = IdapiAuthConfig(idApiUrl, apiConfig.token, Some("membership"))
    IdentityPlayAuthService.unsafeInit(
      idapiConfig,
      tokenVerifierConfig,
      accessClaimsParser = UserFromTokenParser,
    )
  }
  lazy val identityAuthService = new IdentityAuthService(identityPlayAuthService)

  lazy val guardianPatronService =
    new GuardianPatronService(
      supporterProductDataService,
      patronsStripeService,
      backendConfig.stripePatrons.stripeCredentials.publicKey,
      createMetrics,
    )

  lazy val chooseStripe: ChooseStripe = ChooseStripe.createFor(backendConfig.stripeUKMembership, backendConfig.stripeAUMembership, createMetrics)

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
}
