package services

import _root_.play.api.mvc.RequestHeader
import com.gu.identity.play
import com.gu.identity.play.{AccessCredentials, AuthenticatedIdUser}
import configuration.Config

object IdentityAuthService extends AuthenticationService {
  val playAuthService = new play.AuthenticationService {
    override val identityKeys = Config.idKeys

    override lazy val authenticatedIdUserProvider =
      AuthenticatedIdUser.provider(
        AccessCredentials.Cookies.authProvider(identityKeys),
        AccessCredentials.Token.authProvider(identityKeys,"members-data-api")
      )
  }

  override def userId(implicit request: RequestHeader): Option[String] =
    playAuthService.authenticatedUserFor(request).map(_.user.id)

  override def username(implicit request: RequestHeader): Option[String] =
    playAuthService.authenticatedUserFor(request).flatMap(_.user.displayName)
}
