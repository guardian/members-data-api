package services

import com.gu.identity.auth.AccessScope
import models.AccessClaims
import play.api.mvc.RequestHeader

import scala.concurrent.Future

trait AuthenticationService {
  def user(requiredScopes: List[AccessScope])(implicit request: RequestHeader): Future[Option[AccessClaims]]
}
