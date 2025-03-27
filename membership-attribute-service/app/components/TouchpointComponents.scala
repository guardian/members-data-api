package components

import com.gu.aws.ProfileName
import com.gu.config
import com.gu.identity.IdapiService
import com.gu.identity.auth._
import com.gu.identity.play.IdentityPlayAuthService
import com.gu.memsub.subsv2.Catalog
import com.gu.memsub.subsv2.services.{CatalogService, FetchCatalog, SubscriptionService}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.{SafeLogging, ZuoraMetrics}
import com.gu.okhttp.RequestRunners
import com.gu.touchpoint.TouchpointBackendConfig
import com.gu.zuora.rest.SimpleClient
import com.gu.zuora.soap.Client
import com.gu.zuora.{ZuoraSoapService, rest}
import com.typesafe.config.Config
import configuration.Stage
import monitoring.CreateMetrics
import org.apache.pekko.actor.ActorSystem
import org.http4s.Uri
import scalaz.{-\/, \/-}
import scalaz.std.scalaFuture._
import services._
import services.salesforce.{ContactRepository, CreateScalaforce, SimpleContactRepository}
import services.stripe.{BasicStripeService, ChooseStripe, HttpBasicStripeService}
import services.subscription.CancelSubscription
import services.zuora.payment.{PaymentService, SetPaymentCard}
import services.zuora.rest.{SimpleClientZuoraRestService, ZuoraRestService}
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
import scala.concurrent.{ExecutionContext, Future}

class TouchpointComponents(
    stage: Stage,
    createMetrics: CreateMetrics,
    conf: Config,
    supporterProductDataServiceOverride: Option[SupporterProductDataService] = None,
    contactRepositoryOverride: Option[ContactRepository] = None,
    subscriptionServiceOverride: Option[SubscriptionService[Future]] = None,
    zuoraRestServiceOverride: Option[ZuoraRestService] = None,
    catalogServiceOverride: Option[Future[Catalog]] = None,
    zuoraServiceOverride: Option[ZuoraSoapService with HealthCheckableService] = None,
    patronsStripeServiceOverride: Option[BasicStripeService] = None,
    chooseStripeOverride: Option[ChooseStripe] = None,
)(implicit
    system: ActorSystem,
    executionContext: ExecutionContext,
) extends SafeLogging {
  lazy val touchpointConfig = conf.getConfig("touchpoint.backend")
  lazy val environmentConfig = touchpointConfig.getConfig(s"environments." + stage.value)

  lazy val supporterProductDataTable = environmentConfig.getString("supporter-product-data.table")

  lazy val productIds = config.SubsV2ProductIds.load(environmentConfig.getConfig("zuora.productIds"))

  lazy val backendConfig = TouchpointBackendConfig.byEnv(stage.value, touchpointConfig)

  lazy val patronsStripeService: BasicStripeService = {
    lazy val patronsBasicHttpStripeService = new HttpBasicStripeService(backendConfig.stripePatrons, RequestRunners.futureRunner)
    patronsStripeServiceOverride.getOrElse(patronsBasicHttpStripeService)
  }

  lazy val salesforce = CreateScalaforce(backendConfig.salesforce, system.scheduler, configuration.ApplicationName.applicationName)

  lazy val contactRepository: ContactRepository = {
    lazy val simpleContactRepository = new SimpleContactRepository(salesforce)

    contactRepositoryOverride.getOrElse(simpleContactRepository)
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
    new SupporterProductDataService(dynamoClientBuilder.build(), supporterProductDataTable, mapper, createMetrics)

  lazy val supporterProductDataService: SupporterProductDataService =
    supporterProductDataServiceOverride.getOrElse(dynamoSupporterProductDataService)

  private val zuoraMetrics = new ZuoraMetrics(stage.value, configuration.ApplicationName.applicationName)

  lazy val zuoraSoapService = {
    lazy val zuoraSoapClient =
      new Client(
        apiConfig = backendConfig.zuoraSoap,
        httpClient = RequestRunners.configurableFutureRunner(timeout = Duration(30, SECONDS)),
        metrics = zuoraMetrics,
      )

    lazy val simpleZuoraSoapService = new ZuoraSoapService(zuoraSoapClient) with HealthCheckableService {
      override def checkHealth: Boolean = zuoraSoapClient.isReady
    }

    zuoraServiceOverride.getOrElse(simpleZuoraSoapService)
  }

  lazy val zuoraRestClient = SimpleClient(backendConfig.zuoraRest, RequestRunners.configurableFutureRunner(30.seconds))

  lazy val zuoraRestService: ZuoraRestService = {
    lazy val simpleClientZuoraRestService = new SimpleClientZuoraRestService(zuoraRestClient)
    zuoraRestServiceOverride.getOrElse(simpleClientZuoraRestService)
  }

  private lazy val futureCatalogNoPrefix: Future[Catalog] = {
    logger.infoNoPrefix(s"Loading catalog in $stage")
    val catalogRestClient = rest.SimpleClient[Future](backendConfig.zuoraRest, RequestRunners.configurableFutureRunner(60.seconds))
    catalogServiceOverride.getOrElse(
      CatalogService
        .read(FetchCatalog.fromZuoraApi(catalogRestClient)(implicitly, LogPrefix.noLogPrefix), productIds)
        .flatMap {
          case -\/(error) => Future.failed(new RuntimeException(error))
          case \/-(result) => Future.successful(result)
        },
    )
  }
  def futureCatalog(implicit logPrefix: LogPrefix): Future[Catalog] =
    futureCatalogNoPrefix
      .recover { case error =>
        logger.error(scrub"Failed to load the product catalog from Zuora due to: $error")
        throw error
      }

  lazy val subscriptionService: SubscriptionService[Future] = {
    lazy val zuoraSubscriptionService = new SubscriptionService(futureCatalog(_), zuoraRestClient, zuoraSoapService)

    subscriptionServiceOverride.getOrElse(zuoraSubscriptionService)
  }
  lazy val paymentService: PaymentService = new PaymentService(zuoraSoapService)

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
    ChooseStripe.createFor(
      backendConfig.stripeUKMembership,
      backendConfig.stripeAUMembership,
      backendConfig.stripeTortoiseMedia,
    ),
  )

  lazy val paymentDetailsForSubscription: PaymentDetailsForSubscription = new PaymentDetailsForSubscription(paymentService, futureCatalog(_))

  lazy val accountDetailsFromZuora: AccountDetailsFromZuora =
    new AccountDetailsFromZuora(
      createMetrics,
      zuoraRestService,
      contactRepository,
      subscriptionService,
      chooseStripe,
      paymentDetailsForSubscription,
      futureCatalog(_),
    )

  def setPaymentCard(stripePublicKey: String): SetPaymentCard = {
    val stripeService = chooseStripe.serviceForPublicKey(stripePublicKey).toRight(s"No Stripe service for public key: $stripePublicKey")
    new SetPaymentCard(zuoraSoapService, stripeService)
  }

  lazy val cancelSubscription = new CancelSubscription(subscriptionService, zuoraRestService)
}
