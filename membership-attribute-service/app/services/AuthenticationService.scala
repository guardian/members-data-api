package services

import configuration.Config

object AuthenticationService extends com.gu.identity.play.AuthenticationService {
  override def idWebAppSigninUrl(returnUrl: String) = ""

  override val identityKeys = Config.idKeys
}
