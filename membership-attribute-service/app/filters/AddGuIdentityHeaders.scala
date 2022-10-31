package filters

import akka.stream.Materializer
import configuration.Config
import models.UserFromToken
import play.api.mvc._
import services.IdentityAuthService

import scala.concurrent.{ExecutionContext, Future}

/*
 * This is a candidate for inclusion in https://github.com/guardian/memsub-common-play-auth ,
 * this particular version is a tweaked copy from https://github.com/guardian/subscriptions-frontend/blob/ea805479/app/filters/AddGuIdentityHeaders.scala
 */
class AddGuIdentityHeaders(identityAuthService: IdentityAuthService)(implicit val mat: Materializer, ex: ExecutionContext) extends Filter {

  def apply(nextFilter: RequestHeader => Future[Result])(request: RequestHeader): Future[Result] = nextFilter(request) flatMap { result =>
    AddGuIdentityHeaders.fromIdapiIfMissing(request, result, identityAuthService)
  }
}

object AddGuIdentityHeaders {
  val xGuIdentityIdHeaderName = "X-Gu-Identity-Id"
  val xGuMembershipTestUserHeaderName = "X-Gu-Membership-Test-User"
  val identityHeaderNames = Set(xGuIdentityIdHeaderName, xGuMembershipTestUserHeaderName)
  // Identity checks for test users by first name
  def isTestUser(displayName: Option[String]) =
    displayName.flatMap(_.split(' ').headOption).exists(Config.testUsernames.isValid)

  def fromIdapiIfMissing(request: RequestHeader, result: Result, identityAuthService: IdentityAuthService)(implicit
      ec: ExecutionContext,
  ): Future[Result] = {
    if (hasIdentityHeaders(result)) {
      Future.successful(result)
    } else
      identityAuthService.user(requiredScopes = Nil)(request) map {
        case Some(user) => fromUser(result, user)
        case None => result
      }
  }

  def fromUser(result: Result, user: UserFromToken) = result.withHeaders(
    xGuIdentityIdHeaderName -> user.identityId,
    xGuMembershipTestUserHeaderName -> isTestUser(user.username).toString,
  )

  def hasIdentityHeaders(result: Result) = identityHeaderNames.forall(result.header.headers.contains)
}
