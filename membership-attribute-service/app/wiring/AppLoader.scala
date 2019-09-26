package wiring

import actions.CommonActions
import akka.actor.ActorSystem
import components.TouchpointBackends
import configuration.Config
import controllers._
import filters.{AddEC2InstanceHeader, AddGuIdentityHeaders, CheckCacheHeadersFilter}
import loghandling.Logstash
import monitoring.{ErrorHandler, SentryLogging}
import play.api.ApplicationLoader.Context
import play.api.{db, _}
import play.api.db.{ConnectionPool, DBComponents, HikariCPComponents}
import play.api.http.DefaultHttpErrorHandler
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.filters.cors.CORSFilter
import play.filters.csrf.CSRFComponents
import router.Routes
import services.{AttributesFromZuora, MobileSubscriptionServiceImpl, PostgresDatabaseService}

import scala.concurrent.ExecutionContext

class AppLoader extends ApplicationLoader
{
  def load(context: Context) = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }
    SentryLogging.init()
    Logstash.init(Config)
    new MyComponents(context).application
  }
}

class MyComponents(context: Context)
  extends BuiltInComponentsFromContext(context)
    with AhcWSComponents
    with CSRFComponents
    with HikariCPComponents
    with DBComponents
{



  val touchPointBackends = new TouchpointBackends(actorSystem)
  val commonActions = new CommonActions(touchPointBackends, defaultBodyParser)
  override lazy val httpErrorHandler: ErrorHandler =
    new ErrorHandler(environment, configuration, sourceMapper, Some(router), touchPointBackends.normal.identityAuthService)
  val attributesFromZuora = new AttributesFromZuora()

  val dbService = {
    val db = dbApi.database("oneOffStore")
    val jdbcExecutionContext: ExecutionContext = actorSystem.dispatchers.lookup("contexts.jdbc-context")

    PostgresDatabaseService.fromDatabase(db)(jdbcExecutionContext)
  }

  val mobileSubscriptionService = new MobileSubscriptionServiceImpl(wsClient = wsClient)

  lazy val router: Routes = new Routes(
    httpErrorHandler,
    new HealthCheckController(touchPointBackends, controllerComponents),
    new AttributeController(attributesFromZuora, commonActions, controllerComponents, dbService, mobileSubscriptionService),
    new ExistingPaymentOptionsController(commonActions, controllerComponents),
    new AccountController(commonActions, controllerComponents),
    new PaymentUpdateController(commonActions, controllerComponents)
  )

  val postPaths: List[String] = router.documentation.collect { case ("POST", path, _) => path }

  override lazy val httpFilters: Seq[EssentialFilter] = Seq(
    new CheckCacheHeadersFilter(),
    csrfFilter,
    new AddEC2InstanceHeader(wsClient),
    new AddGuIdentityHeaders(touchPointBackends.normal.identityAuthService),
    CORSFilter(corsConfig = Config.mmaUpdateCorsConfig, pathPrefixes = postPaths),
    CORSFilter(corsConfig = Config.corsConfig, pathPrefixes = Seq("/user-attributes"))
  )

}
