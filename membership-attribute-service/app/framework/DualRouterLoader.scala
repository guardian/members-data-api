package framework

import com.softwaremill.macwire._
import components._
import monitoring.SentryLogging
import play.api.ApplicationLoader.Context
import play.api._
import play.api.routing.Router
import router.Routes

/*
 * so the whole point of this is to allow the switching of controller dependencies at runtime
 * while supporting compile time dependency injection. the only way I can think of to do that is to have
 * two banks of instantiated controller classes with different backends mixed in, and then two routers
 */
class DualRouterLoader extends ApplicationLoader {

  // we have to override application so it picks up the DualHttpRequestHandler rather than the lazy val in BuiltInComponents
  class DualRouterComponents(components: AllComponentTraits, prodRouter: Router, testRouter: Router) extends AllComponents(components)  { self: HttpFilterComponents =>
    override lazy val httpRequestHandler = new DualHttpRequestHandler(prodRouter, testRouter, httpErrorHandler, httpConfiguration, httpFilters:_*)
    override lazy val httpErrorHandler = new JsonHttpErrorHandler()
    override lazy val application: Application = new DefaultApplication(environment, applicationLifecycle, injector, configuration, httpRequestHandler, httpErrorHandler, actorSystem, Plugins.empty)
  }

  def load(context: Context) = {
    Logger.configure(context.environment)
    SentryLogging.init()

    trait InjectedRouter { self: ControllerComponents with BuiltInComponents =>
      override lazy val router: Router = wire[Routes]
      lazy val prefix = "/"
    }

    val components = new BuiltInComponentsFromContext(context) with AllComponentTraits { override val router: Router = Router.empty }
    val prod = new AllComponents(components) with NormalTouchpointComponents with ControllerComponents with InjectedRouter
    val test = new AllComponents(components) with TestTouchpointComponents with ControllerComponents with InjectedRouter
    (new DualRouterComponents(components, prod.router, test.router) with HttpFilterComponents).application
  }
}

