package filters

import akka.stream.Materializer
import com.gu.identity.model.User
import configuration.Config
import play.api.mvc._
import services.IdentityAuthService

import scala.concurrent.{ExecutionContext, Future}

/*
 * This is a candidate for inclusion in https://github.com/guardian/memsub-common-play-auth ,
 * this particular version is a tweaked copy from https://github.com/guardian/subscriptions-frontend/blob/ea805479/app/filters/AddGuIdentityHeaders.scala
 */
class AddGuIdentityHeaders(identityAuthService: IdentityAuthService) (implicit val mat: Materializer, ex: ExecutionContext) extends Filter {

  def apply(nextFilter: RequestHeader => Future[Result])(request: RequestHeader): Future[Result] = nextFilter(request) flatMap {
    result => AddGuIdentityHeaders.headersFor(request, result, identityAuthService)
  }
}
object AddGuIdentityHeaders {

  //Identity checks for test users by first name
  def isTestUser(displayName: Option[String]) =
    displayName.flatMap(_.split(' ').headOption).exists(Config.testUsernames.isValid)

  def headersFor(request: RequestHeader, result: Result, identityAuthService: IdentityAuthService)(implicit ec: ExecutionContext): Future[Result] = {
    identityAuthService.user(request) map {
      case Some(user) => result.withHeaders(
        "X-Gu-Identity-Id" -> user.id,
        "X-Gu-Membership-Test-User" -> isTestUser(user.publicFields.displayName).toString
      )
      case None => result
    }
  }
}
