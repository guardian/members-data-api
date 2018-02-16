package filters

import javax.inject.Inject

import akka.stream.Materializer
import configuration.Config
import play.api.http.HeaderNames
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc._
import services.IdentityAuthService

import scala.concurrent.Future

/*
 * This is a candidate for inclusion in https://github.com/guardian/memsub-common-play-auth ,
 * this particular version is a tweaked copy from https://github.com/guardian/subscriptions-frontend/blob/ea805479/app/filters/AddGuIdentityHeaders.scala
 */
class AddGuIdentityHeaders (implicit val mat: Materializer) extends Filter {

  def apply(nextFilter: RequestHeader => Future[Result])(request: RequestHeader): Future[Result] = for {
    result <- nextFilter(request)
  } yield AddGuIdentityHeaders.headersFor(request, result)
}

object AddGuIdentityHeaders {

  def headersFor(request: RequestHeader, result: Result) = (for {
    user <- IdentityAuthService.playAuthService.authenticatedUserFor(request)
  } yield result.withHeaders(
    "X-Gu-Identity-Id" -> user.id,
    "X-Gu-Identity-Credentials-Type" -> user.credentials.getClass.getSimpleName,
    "X-Gu-Membership-Test-User" -> Config.testUsernames.isValid(user.id).toString
    )).getOrElse(result)
}
