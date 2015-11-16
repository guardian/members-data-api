package mocks

import play.api.mvc.{Cookie, RequestHeader}
import services.AuthenticationService

class AuthenticationServiceFake extends AuthenticationService {

  val validUserId = "123"
  val invalidUserId = "456"
  val validUserCookie = Cookie("validUser", "true")
  val invalidUserCookie = Cookie("invalidUser", "true")

  override def username(implicit request: RequestHeader) = ???
  override def userId(implicit request: RequestHeader) = request.cookies.headOption match {
    case Some(c) if c == validUserCookie => Some(validUserId)
    case Some(c) if c == invalidUserCookie => Some(invalidUserId)
    case _ => None
  }
}