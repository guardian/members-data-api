package framework
import configuration.Config
import configuration.Config.BackendConfig._
import play.api.mvc.RequestHeader
import play.api.routing.Router
import services.IdentityAuthService
import scalaz.syntax.std.option._

object RouterSwitcher
{
  private val salesforceSecretParam = "secret"

  private def routerFromCookie(prodRouter: Router, testRouter: Router, request: RequestHeader): Router = {
    if (IdentityAuthService.username(request).exists(Config.testUsernames.isValid)) {
      println("UAT")
      testRouter
    } else {
      println("PROD")
      testRouter
    }
  }

  /**
   * Pick a router based either on the request cookie
   * or if from salesforce then the salesforce secret param
   */
  def selectRouter(prodRouter: Router, testRouter: Router, request: RequestHeader): Router = {
    val backend = Seq(test, default).find(_.salesforceConfig.secret.some == request.getQueryString(salesforceSecretParam))
    backend.fold[Router](routerFromCookie(prodRouter, testRouter, request))(b => if(b == test) testRouter else prodRouter)
  }
}
