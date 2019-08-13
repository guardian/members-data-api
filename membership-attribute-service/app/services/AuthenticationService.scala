package services

import com.gu.identity.model.User
import play.api.mvc.RequestHeader

import scala.concurrent.Future

trait AuthenticationService {
  def user(implicit request: RequestHeader): Future[Option[User]]
}
