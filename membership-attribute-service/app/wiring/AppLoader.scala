package wiring

import actions.CommonActions
import ch.qos.logback.classic.LoggerContext
import com.gu.memsub.subsv2.services.{CatalogService, SubscriptionService}
import com.gu.monitoring.SafeLoggerImpl
import com.gu.zuora.ZuoraSoapService
import components.TouchpointBackends
import configuration.{CreateTestUsernames, SentryConfig, Stage}
import controllers._
import filters._
import monitoring.{CreateRealMetrics, ErrorHandler, SentryLogging}
import org.apache.pekko.actor.ActorSystem
import org.slf4j.LoggerFactory
import play.api.ApplicationLoader.Context
import play.api._
import play.api.db.{DBComponents, HikariCPComponents}
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.filters.cors.{CORSConfig, CORSFilter}
import play.filters.csrf.CSRFComponents
import router.Routes
import services.mail.{QueueName, SendEmail, SendEmailToSQS}
import services.salesforce.ContactRepository
import services.stripe.{BasicStripeService, ChooseStripe}
import services.zuora.rest.ZuoraRestService
import services._

import scala.concurrent.{ExecutionContext, Future}

class AppLoader extends ApplicationLoader {
  def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }
    val loggerPackageName = classOf[SafeLoggerImpl].getPackageName
    // at the moment we get SafeLogger.scala:49 type things in the logs
    // adding to the FrameworkPackages makes logback skip over and find the real line of code
    LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext].getFrameworkPackages.add(loggerPackageName)

    val config = context.initialConfiguration.underlying
    SentryLogging.init(new SentryConfig(config))
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
  lazy val isProd = stage.value == "PROD"
  lazy val createMetrics = new CreateRealMetrics(stage)

  lazy val supporterProductDataServiceOverride: Option[SupporterProductDataService] = None
  lazy val contactRepositoryOverride: Option[ContactRepository] = None
  lazy val subscriptionServiceOverride: Option[SubscriptionService[Future]] = None
  lazy val zuoraRestServiceOverride: Option[ZuoraRestService] = None
  lazy val catalogServiceOverride: Option[CatalogService[Future]] = None
  lazy val zuoraSoapServiceOverride: Option[ZuoraSoapService with HealthCheckableService] = None
  lazy val patronsStripeServiceOverride: Option[BasicStripeService] = None
  lazy val chooseStripeOverride: Option[ChooseStripe] = None

  lazy val emailQueueName = QueueName(if (isProd) "braze-emails-PROD" else "braze-emails-CODE")
  lazy val sendEmail: SendEmail = new SendEmailToSQS(emailQueueName)

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
    chooseStripeOverride,
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
    new AccountController(commonActions, controllerComponents, dbService, sendEmail, createMetrics),
    new PaymentUpdateController(commonActions, controllerComponents, sendEmail, createMetrics),
    new ContactController(commonActions, controllerComponents, createMetrics),
  )

  val postPaths: Seq[String] = router.documentation.collect { case ("POST", path, _) => path }

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
