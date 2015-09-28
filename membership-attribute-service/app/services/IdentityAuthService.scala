package services

import _root_.play.api.mvc.RequestHeader
import com.gu.identity.play
import configuration.Config

object IdentityAuthService extends AuthenticationService {
  val playAuthService = new play.AuthenticationService {
    override def idWebAppSigninUrl(returnUrl: String) = ""
    override val identityKeys = Config.idKeys
  }

  override def userId(implicit request: RequestHeader): Option[String] =
    playAuthService.authenticatedUserFor(request).map(_.id)
}
