package services

import play.api.mvc.RequestHeader

trait AuthenticationService {
  def userId(implicit request: RequestHeader): Option[String]
  def username(implicit request: RequestHeader): Option[String]
}
