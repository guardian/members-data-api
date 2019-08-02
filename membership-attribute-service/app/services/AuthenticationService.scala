package services

import com.gu.identity.model.User
import play.api.mvc.RequestHeader

import scala.concurrent.Future

//TODO WHAT IS THIS FOR?
trait AuthenticationService {
  //TODO should this be implicit ???
  def user(implicit request: RequestHeader): Future[Option[User]]
}
