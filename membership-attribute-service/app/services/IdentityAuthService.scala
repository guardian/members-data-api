package services

import _root_.play.api.mvc.RequestHeader
import com.gu.identity.play
import configuration.Config

object IdentityAuthService extends AuthenticationService {
  val playAuthService = new play.AuthenticationService {
    override val identityKeys = Config.idKeys
  }

  override def userId(implicit request: RequestHeader): Option[String] =
    playAuthService.authenticatedUserFor(request).map(_.user.id)

  override def username(implicit request: RequestHeader): Option[String] =
    playAuthService.authenticatedUserFor(request).flatMap(_.user.displayName)
}
