package framework

import com.softwaremill.macwire._
import components._
import play.api.ApplicationLoader.Context
import play.api._
import play.api.libs.ws.ning.NingWSComponents
import play.api.routing.Router
import router.Routes

/*
 * so the whole point of this is to allow the switching of controller dependencies at runtime
 * while supporting compile time dependency injection. the only way I can think of to do that is to have
 * two banks of instantiated controller classes with different backends mixed in, and then two routers
 */
class DualRouterLoader extends ApplicationLoader {

  // we have to override application so it picks up the DualHttpRequestHandler rather than the lazy val in BuiltInComponents
  class DualRouterComponents(components: BuiltInComponents, prodRouter: Router, testRouter: Router) extends ClonedComponents(components)  {
    override lazy val application: Application = new DefaultApplication(environment, applicationLifecycle, injector, configuration, httpRequestHandler, httpErrorHandler, actorSystem, Plugins.empty)
    override lazy val httpRequestHandler = new DualHttpRequestHandler(prodRouter, testRouter, httpErrorHandler, httpConfiguration)
  }

  def load(context: Context) = {

    trait InjectedRouter { self: ControllerComponents with BuiltInComponents =>
      override lazy val router: Router = wire[Routes]
      lazy val prefix = "/"
    }

    //instances within these traits will only be loaded once
    trait Common extends ConfigComponents with NingWSComponents
    val components = new BuiltInComponentsFromContext(context) { val router: Router = Router.empty }

    val prod = new ClonedComponents(components) with Common with  NormalTouchpointComponents with ControllerComponents with InjectedRouter
    val test = new ClonedComponents(components) with Common with TestTouchpointComponents with ControllerComponents with InjectedRouter
    new DualRouterComponents(components, prod.router, test.router).application
  }
}

