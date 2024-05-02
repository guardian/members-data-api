package actions
import org.apache.pekko.stream.Materializer
import com.gu.identity.RedirectAdviceResponse
import com.gu.identity.auth.AccessScope
import com.gu.monitoring.SafeLogger.LogPrefix
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

  // TODO: Might need a better name as authoriseForRecentLogin checks for recency and scopes
  def AuthorizeForScopes(requiredScopes: List[AccessScope]): ActionBuilder[AuthenticatedUserAndBackendRequest, AnyContent] =
    NoCacheAction andThen new AuthAndBackendViaAuthLibAction(touchpointBackends, requiredScopes, isTestUser)

  def AuthorizeForRecentLogin(
      howToHandleRecencyOfSignedIn: HowToHandleRecencyOfSignedIn,
      requiredScopes: List[AccessScope],
  ): ActionBuilder[AuthAndBackendRequest, AnyContent] =
    NoCacheAction andThen new AuthAndBackendViaIdapiAction(touchpointBackends, howToHandleRecencyOfSignedIn, isTestUser, requiredScopes)

  // TODO: Is this redundant, given that authoriseForRecentLogin checks for recency and scopes?
  def AuthorizeForRecentLoginAndScopes(
      howToHandleRecencyOfSignedIn: HowToHandleRecencyOfSignedIn,
      requiredScopes: List[AccessScope],
  ): ActionBuilder[AuthenticatedUserAndBackendRequest, AnyContent] =
    NoCacheAction andThen
      new AuthAndBackendViaIdapiAction(touchpointBackends, howToHandleRecencyOfSignedIn, isTestUser, requiredScopes) andThen
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
) extends WrappedRequest[A](request) {
  implicit val logPrefix: LogPrefix = user.logPrefix
}

class AuthAndBackendRequest[A](
    val redirectAdvice: RedirectAdviceResponse,
    val touchpoint: TouchpointComponents,
    request: Request[A],
) extends WrappedRequest[A](request) {
  implicit val logPrefix: LogPrefix = new LogPrefix {
    override def message: String = redirectAdvice.userId.getOrElse("no-identity-id")
  }
}
