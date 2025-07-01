package filters

import org.apache.pekko.stream.Materializer
import filters.AddGuIdentityHeaders.{identityHeaderNames, xGuIdentityIdHeaderName, xGuMembershipTestUserHeaderName}
import models.UserFromToken
import play.api.mvc._
import services.IdentityAuthService

import scala.concurrent.{ExecutionContext, Future}

/*
 * This is a candidate for inclusion in https://github.com/guardian/memsub-common-play-auth ,
 * this particular version is a tweaked copy from https://github.com/guardian/subscriptions-frontend/blob/ea805479/app/filters/AddGuIdentityHeaders.scala
 */

class AddGuIdentityHeadersFilter(addGuIdentityHeaders: AddGuIdentityHeaders)(implicit val mat: Materializer, ex: ExecutionContext) extends Filter {

  def apply(nextFilter: RequestHeader => Future[Result])(request: RequestHeader): Future[Result] = nextFilter(request) flatMap { result =>
    addGuIdentityHeaders.fromIdapiIfMissing(request, result)
  }
}

class AddGuIdentityHeaders(identityAuthService: IdentityAuthService, testUserChecker: TestUserChecker) {

  def fromUser(result: Result, user: UserFromToken): Result = result.withHeaders(
    xGuIdentityIdHeaderName -> user.identityId,
    xGuMembershipTestUserHeaderName -> testUserChecker.isTestUser(user.primaryEmailAddress)(user.logPrefix).toString,
  )

  def fromIdapiIfMissing(request: RequestHeader, result: Result)(implicit ex: ExecutionContext): Future[Result] = {
    if (hasIdentityHeaders(result)) {
      Future.successful(result)
    } else
      identityAuthService.user(requiredScopes = Nil)(request) map {
        case Right(user) => fromUser(result, user)
        case Left(_) => result
      }
  }

  def hasIdentityHeaders(result: Result) = identityHeaderNames.forall(result.header.headers.contains)
}

object AddGuIdentityHeaders {
  val xGuIdentityIdHeaderName = "X-Gu-Identity-Id"
  val xGuMembershipTestUserHeaderName = "X-Gu-Membership-Test-User"
  val identityHeaderNames = Set(xGuIdentityIdHeaderName, xGuMembershipTestUserHeaderName)
}
