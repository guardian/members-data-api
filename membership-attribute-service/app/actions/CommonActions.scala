package actions
import akka.stream.Materializer
import com.gu.identity.RedirectAdviceResponse
import com.gu.identity.auth.AccessScope
import components.{TouchpointBackends, TouchpointComponents}
import controllers.NoCache
import filters.IsTestUser
import models.UserFromToken
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

sealed trait HowToHandleRecencyOfSignedIn
case object Return401IfNotSignedInRecently extends HowToHandleRecencyOfSignedIn
case object ContinueRegardlessOfSignInRecency extends HowToHandleRecencyOfSignedIn

class CommonActions(touchpointBackends: TouchpointBackends, bodyParser: BodyParser[AnyContent], isTestUser: IsTestUser)(implicit
    ex: ExecutionContext,
    mat: Materializer,
) {
  def noCache(result: Result): Result = NoCache(result)

  val NoCacheAction = resultModifier(noCache)

  def AuthorizeForScopes(requiredScopes: List[AccessScope]): ActionBuilder[AuthenticatedUserAndBackendRequest, AnyContent] =
    NoCacheAction andThen new AuthAndBackendViaAuthLibAction(touchpointBackends, requiredScopes, isTestUser)

  def AuthorizeForRecentLogin(howToHandleRecencyOfSignedIn: HowToHandleRecencyOfSignedIn, requiredScopes: List[AccessScope]): ActionBuilder[AuthAndBackendRequest, AnyContent] =
    NoCacheAction andThen new AuthAndBackendViaIdapiAction(touchpointBackends, howToHandleRecencyOfSignedIn, isTestUser, requiredScopes)

  def AuthorizeForRecentLogin(
      howToHandleRecencyOfSignedIn: HowToHandleRecencyOfSignedIn,
      requiredScopes: List[AccessScope],
  ): ActionBuilder[AuthenticatedUserAndBackendRequest, AnyContent] =
    NoCacheAction andThen
      new AuthAndBackendViaIdapiAction(touchpointBackends, howToHandleRecencyOfSignedIn, isTestUser) andThen
      new AuthAndBackendViaAuthLibAction(touchpointBackends, requiredScopes, isTestUser)

  private def resultModifier(f: Result => Result) = new ActionBuilder[Request, AnyContent] {
    override val parser = bodyParser
    override val executionContext = ex
    def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = block(request).map(f)
  }
}

class AuthenticatedUserAndBackendRequest[A](
    val user: UserFromToken,
    val touchpoint: TouchpointComponents,
    val request: Request[A],
) extends WrappedRequest[A](request)

class AuthAndBackendRequest[A](
    val redirectAdvice: RedirectAdviceResponse,
    val touchpoint: TouchpointComponents,
    request: Request[A],
) extends WrappedRequest[A](request)
