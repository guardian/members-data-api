package wiring

import actions.CommonActions
import components.TouchpointBackends
import configuration.Config
import controllers._
import filters.{AddEC2InstanceHeader, AddGuIdentityHeaders, CheckCacheHeadersFilter}
import loghandling.Logstash
import monitoring.{ErrorHandler, SentryLogging}
import play.api.ApplicationLoader.Context
import play.api._
import play.api.http.DefaultHttpErrorHandler
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.filters.cors.CORSFilter
import play.filters.csrf.CSRFComponents
import router.Routes
import services.AttributesFromZuora

class AppLoader extends ApplicationLoader {
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
    with CSRFComponents {

  val touchPointBackends = new TouchpointBackends(actorSystem)
  val commonActions = new CommonActions(touchPointBackends, defaultBodyParser)
  override lazy val httpErrorHandler: ErrorHandler =
    new ErrorHandler(environment, configuration, sourceMapper, Some(router))
  val attributesFromZuora = new AttributesFromZuora()
  lazy val router: Routes = new Routes(
    httpErrorHandler,
    new HealthCheckController(touchPointBackends, controllerComponents),
    new AttributeController(attributesFromZuora, commonActions, controllerComponents),
    new ExistingPaymentOptionsController(commonActions, controllerComponents),
    new AccountController(commonActions, controllerComponents),
    new PaymentUpdateController(commonActions, controllerComponents)
  )

  val postPaths: List[String] = router.documentation.collect { case ("POST", path, _) => path }

  override lazy val httpFilters: Seq[EssentialFilter] = Seq(
    new CheckCacheHeadersFilter(),
    csrfFilter,
    new AddEC2InstanceHeader(wsClient),
    new AddGuIdentityHeaders(),
    CORSFilter(corsConfig = Config.mmaUpdateCorsConfig, pathPrefixes = postPaths),
    CORSFilter(corsConfig = Config.corsConfig, pathPrefixes = Seq("/user-attributes"))
  )
}
