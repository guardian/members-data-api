package framework

import play.api.mvc.{Handler, RequestHeader}
import play.api.routing.Router
import play.api.routing.Router.Routes

/**
  * Created by tverran on 23/11/2015.
  */
class DualRouter(prodRouter: Router, testRouter: Router) extends Router {
  override def routes: Routes = prodRouter.routes
  override def withPrefix(prefix: String): Router = prodRouter.withPrefix(prefix)
  override def documentation: Seq[(String, String, String)] = prodRouter.documentation
  override def handlerFor(request: RequestHeader): Option[Handler] = {
    RouterSwitcher.selectRouter(prodRouter, testRouter, request).routes.lift(request)
  }
}
