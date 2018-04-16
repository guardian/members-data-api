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
    new AccountController(commonActions, controllerComponents)
  )

  val regularCorsPaths = Seq(
    "/user-attributes/me/membership",
    "/user-attributes/me/features",
    "/user-attributes/me"
  )

  val mmaPaths = Seq(
    "/user-attributes/me/mma-digitalpack",
    "/user-attributes/me/mma-monthlycontribution",
    "/user-attributes/me/mma-membership",
    "/user-attributes/me/mma-paper")

  val mmaUpdatePaths = Seq(
    "/user-attributes/me/membership-update-card",
    "/user-attributes/me/digitalpack-update-card",
    "/user-attributes/me/paper-update-card",
    "/user-attributes/me/contribution-update-card",
    "/user-attributes/me/cancel-regular-contribution",
    "/user-attributes/me/contribution-update-amount")

  override lazy val httpFilters: Seq[EssentialFilter] = Seq(
    new CheckCacheHeadersFilter(),
    csrfFilter,
    new AddEC2InstanceHeader(wsClient),
    new AddGuIdentityHeaders(),
    CORSFilter(corsConfig = Config.mmaCorsConfig, pathPrefixes = mmaPaths),
    CORSFilter(corsConfig = Config.mmaUpdateCorsConfig, pathPrefixes = mmaUpdatePaths),
    CORSFilter(corsConfig = Config.corsConfig, pathPrefixes = regularCorsPaths)
  )
}
