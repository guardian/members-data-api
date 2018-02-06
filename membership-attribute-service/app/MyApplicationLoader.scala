import configuration.Config
import controllers._
import filters.{AddEC2InstanceHeader, AddGuIdentityHeaders, CheckCacheHeadersFilter}
import loghandling.Logstash
import monitoring.{ErrorHandler, SentryLogging}
import play.api.ApplicationLoader.Context
import play.api._
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.filters.csrf.CSRFComponents
import play.filters.headers.{SecurityHeadersConfig, SecurityHeadersFilter}
import router.Routes

class MyApplicationLoader extends ApplicationLoader {
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
{


  override lazy val httpErrorHandler: ErrorHandler =
    new ErrorHandler(environment, configuration, sourceMapper, Some(router))

  lazy val router: Routes = new Routes(
    httpErrorHandler,
    new HealthCheckController(),
    new AttributeController(),
    new AccountController(),
    new BehaviourController()
  )

  override lazy val httpFilters: Seq[EssentialFilter] = Seq(
    new AddEC2InstanceHeader(wsClient),
    new AddGuIdentityHeaders(),
    new CheckCacheHeadersFilter()
  )

}
