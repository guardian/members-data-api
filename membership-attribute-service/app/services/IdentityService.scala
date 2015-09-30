package services

import configuration.Config
import com.gu.identity.play.{IdMinimalUser, AuthenticationService}
import monitoring.CloudWatch
import play.api.mvc.RequestHeader

object IdentityService extends AuthenticationService {
  override def idWebAppSigninUrl(returnUrl: String) = ""

  override val identityKeys = Config.idKeys

  val metrics = CloudWatch("IdentityService")

  override def authenticatedUserFor[A](request: RequestHeader): Option[IdMinimalUser] =
    super.authenticatedUserFor(request) orElse {
      metrics.put("Auth failure", 1)
      None
    }
}
