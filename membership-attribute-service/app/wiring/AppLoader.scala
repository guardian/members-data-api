package wiring

import actions.CommonActions
import akka.actor.ActorSystem
import components.TouchpointBackends
import configuration.{CreateTestUsernames, LogstashConfig, SentryConfig, Stage}
import controllers._
import filters._
import loghandling.Logstash
import monitoring.{CreateMetrics, ErrorHandler, SentryLogging}
import play.api.ApplicationLoader.Context
import play.api._
import play.api.db.{DBComponents, HikariCPComponents}
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.filters.cors.{CORSConfig, CORSFilter}
import play.filters.csrf.CSRFComponents
import router.Routes
import services.catalog.CatalogService
import services.salesforce.ContactRepository
import services.stripe.BasicStripeService
import services.subscription.SubscriptionService
import services.zuora.rest.ZuoraRestService
import services.zuora.soap.ZuoraSoapService
import services.{
  ContributionsStoreDatabaseService,
  HealthCheckableService,
  MobileSubscriptionServiceImpl,
  PostgresDatabaseService,
  SupporterProductDataService,
}

import scala.concurrent.{ExecutionContext, Future}

class AppLoader extends ApplicationLoader {
  def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }
    val config = context.initialConfiguration.underlying
    SentryLogging.init(new SentryConfig(config))
    Logstash.init(new LogstashConfig(config))
    createMyComponents(context).application
  }

  protected def createMyComponents(context: Context) =
    new MyComponents(context)
}

class MyComponents(context: Context)
    extends BuiltInComponentsFromContext(context)
    with AhcWSComponents
    with CSRFComponents
    with HikariCPComponents
    with DBComponents {

  lazy val config = context.initialConfiguration.underlying
  lazy val stage = Stage(config.getString("stage"))
  lazy val createMetrics = new CreateMetrics(stage)

  lazy val supporterProductDataServiceOverride: Option[SupporterProductDataService] = None
  lazy val contactRepositoryOverride: Option[ContactRepository] = None
  lazy val subscriptionServiceOverride: Option[SubscriptionService] = None
  lazy val zuoraRestServiceOverride: Option[ZuoraRestService] = None
  lazy val catalogServiceOverride: Option[CatalogService] = None
  lazy val zuoraSoapServiceOverride: Option[ZuoraSoapService with HealthCheckableService] = None
  lazy val patronsStripeServiceOverride: Option[BasicStripeService] = None

  lazy val touchPointBackends = new TouchpointBackends(
    actorSystem,
    config,
    createMetrics,
    supporterProductDataServiceOverride,
    contactRepositoryOverride,
    subscriptionServiceOverride,
    zuoraRestServiceOverride,
    catalogServiceOverride,
    zuoraSoapServiceOverride,
    patronsStripeServiceOverride,
  )
  private val isTestUser = new IsTestUser(CreateTestUsernames.from(config))
  private val addGuIdentityHeaders = new AddGuIdentityHeaders(touchPointBackends.normal.identityAuthService, isTestUser)
  lazy val commonActions = new CommonActions(touchPointBackends, defaultBodyParser, isTestUser)
  override lazy val httpErrorHandler: ErrorHandler =
    new ErrorHandler(
      environment,
      configuration,
      devContext.map(_.sourceMapper),
      Some(router),
      touchPointBackends.normal.identityAuthService,
      addGuIdentityHeaders,
    )
  implicit val system: ActorSystem = actorSystem

  lazy val dbService: ContributionsStoreDatabaseService = {
    val db = dbApi.database("oneOffStore")
    val jdbcExecutionContext: ExecutionContext = actorSystem.dispatchers.lookup("contexts.jdbc-context")

    PostgresDatabaseService.fromDatabase(db)(jdbcExecutionContext)
  }

  lazy val mobileSubscriptionService = new MobileSubscriptionServiceImpl(wsClient = wsClient, config)

  lazy val router: Routes = new Routes(
    httpErrorHandler,
    new HealthCheckController(touchPointBackends, controllerComponents),
    new AttributeController(commonActions, controllerComponents, dbService, mobileSubscriptionService, addGuIdentityHeaders, createMetrics),
    new ExistingPaymentOptionsController(commonActions, controllerComponents, createMetrics),
    new AccountController(commonActions, controllerComponents, dbService, createMetrics),
    new PaymentUpdateController(commonActions, controllerComponents, createMetrics),
    new ContactController(commonActions, controllerComponents, createMetrics),
  )

  val postPaths: List[String] = router.documentation.collect { case ("POST", path, _) => path }

  lazy val corsConfig = new CorsConfig(configuration)

  override lazy val httpFilters: Seq[EssentialFilter] = Seq(
    new CheckCacheHeadersFilter(),
    csrfFilter,
    new AddEC2InstanceHeader(wsClient),
    new AddGuIdentityHeadersFilter(addGuIdentityHeaders),
    CORSFilter(corsConfig = corsConfig.mmaUpdateCorsConfig, pathPrefixes = postPaths),
    CORSFilter(corsConfig = corsConfig.corsConfig, pathPrefixes = Seq("/user-attributes")),
  )
}

class CorsConfig(val configuration: Configuration) {
  lazy val corsConfig = CORSConfig.fromConfiguration(configuration)

  lazy val mmaUpdateCorsConfig = corsConfig.copy(
    isHttpHeaderAllowed = Seq("accept", "content-type", "csrf-token", "origin").contains(_),
    isHttpMethodAllowed = Seq("POST", "OPTIONS").contains(_),
  )
}
