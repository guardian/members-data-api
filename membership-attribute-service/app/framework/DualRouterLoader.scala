package framework

import com.softwaremill.macwire._
import components._
import play.api.ApplicationLoader.Context
import play.api._
import play.api.http.DefaultHttpRequestHandler
import play.api.routing.Router
import router.Routes

class DualRouterLoader extends ApplicationLoader {

  def load(context: Context) = {
    Logger.configure(context.environment)

    trait InjectedRouter { self: ControllerComponents with BuiltInComponents =>
      override lazy val router: Router = wire[Routes]
      lazy val prefix = "/"
    }

    val common = new BuiltInComponentsFromContext(context) with AllComponentTraits { override val router: Router = Router.empty }
    val prod = new AllComponents(common) with NormalTouchpointComponents with ControllerComponents with InjectedRouter
    val test = new AllComponents(common) with TestTouchpointComponents with ControllerComponents with InjectedRouter

    new AllComponents(common) with HttpFilterComponents {
      override lazy val router: Router = new DualRouter(prod.router, test.router)
      override lazy val httpRequestHandler = wire[DefaultHttpRequestHandler]
      override lazy val httpErrorHandler = wire[JsonHttpErrorHandler]
      override lazy val application = wire[DefaultApplication]
      lazy val plugins = Plugins.empty
    }.application
  }
}

