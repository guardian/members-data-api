package actions

import configuration.Config
import configuration.Config.BackendConfig
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{ActionRefiner, Request, Result}
import repositories.MembershipAttributesSerializer
import services.{DynamoAttributeService, IdentityAuthService}

import scala.concurrent.Future

object WithBackendFromCookieAction extends ActionRefiner[Request, BackendRequest] {
  override protected def refine[A](request: Request[A]): Future[Either[Result, BackendRequest[A]]] = Future {
    val backendConf =
      if (IdentityAuthService.username(request).exists(Config.testUsernames.isValid))
        BackendConfig.test
      else
        BackendConfig.default

    val attrService = DynamoAttributeService(MembershipAttributesSerializer(backendConf.dynamoTable))

    Right(new BackendRequest[A](backendConf, attrService, request))
  }
}
