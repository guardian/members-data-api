package actions
import com.gu.membership.touchpoint.TouchpointBackendConfig
import com.gu.monitoring.ServiceMetrics
import configuration.Config
import play.api.mvc.{Result, Request, ActionRefiner}
import services.IdentityAuthService
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future

object WithTouchpointFromCookieAction extends ActionRefiner[Request, TouchpointRequest] {
  override protected def refine[A](request: Request[A]): Future[Either[Result, TouchpointRequest[A]]] = Future {
    val backendConf =
      if (IdentityAuthService.username(request).exists(Config.testUsernames.isValid))
        TouchpointBackendConfig.byEnv(Config.testTouchpointBackendStage, Config.config.getConfig("touchpoint.backend"))
      else
        TouchpointBackendConfig.byEnv(Config.defaultTouchpointBackendStage, Config.config.getConfig("touchpoint.backend"))
    Right(new TouchpointRequest[A](backendConf, new ServiceMetrics(backendConf.zuoraRest.envName, Config.applicationName,_: String), request))
  }
}
