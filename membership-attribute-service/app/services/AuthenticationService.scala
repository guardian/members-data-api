package services

import models.AccessClaims
import play.api.mvc.RequestHeader

import scala.concurrent.Future

trait AuthenticationService {
  def user(requiredScopes: List[String])(implicit request: RequestHeader): Future[Option[AccessClaims]]
}
