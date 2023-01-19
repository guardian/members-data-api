package models

import com.gu.identity.auth.{AccessScope => IdentityAccessScope}

/** <p>Scope that endpoints need from access tokens before they can carry out requests. For background, see <a
  * href="https://www.oauth.com/oauth2-servers/scope/defining-scopes">Oauth scopes</a></p>
  *
  * <p>To add scopes, the process is described in <a
  * href="https://github.com/guardian/identity/blob/main/identity-auth-core/src/main/scala/com/gu/identity/auth/AccessScope.scala">IdentityAccessScope</a></p>
  *
  * <p><strong>Scope name values have to match the values stored in Okta.</strong></p>
  */
object AccessScope {

  /** Allows the client to read basic non-sensitive data relating to the user's Guardian subscriptions and contributions.
    */
  case object readSelf extends IdentityAccessScope {
    val name = "guardian.members-data-api.read.self"
  }

  /** Allows the client to read the complete data relating to the user's Guardian subscriptions and contributions.
    */
  case object completeReadSelf extends IdentityAccessScope {
    val name = "guardian.members-data-api.complete.read.self.secure"
  }

  /** Allows the client to update data relating to the user's Guardian subscriptions and contributions.
    */
  case object updateSelf extends IdentityAccessScope {
    val name = "guardian.members-data-api.update.self.secure"
  }
}
