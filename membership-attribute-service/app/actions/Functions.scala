package actions

import com.gu.identity.play.IdMinimalUser
import models.{ApiError, ApiErrors}
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.Security.AuthenticatedBuilder
import play.api.mvc._
import services.AuthenticationService._

import scala.concurrent.Future

case class AuthenticatedException(user: IdMinimalUser, ex: Throwable)
  extends Exception(s"Error for user ${user.id} - ${ex.getMessage}", ex, true, false)

/**
 * These ActionFunctions serve as components that can be composed to build the
 * larger, more-generally useful pipelines in 'CommonActions'.
 *
 * https://www.playframework.com/documentation/2.3.x/ScalaActionsComposition
 */
object Functions extends Results {

  private val logger = Logger(this.getClass)

  def resultModifier(f: Result => Result) = new ActionBuilder[Request] {
    def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = block(request).map(f)
  }

  private def unauthenticated(request: RequestHeader): Result =
    ApiErrors(List(ApiError("Unauthorised", "Valid GU_U and SC_GU_U cookies are required.", 401))).toResult

  val authenticatedExceptionHandler = new ActionFunction[AuthRequest, AuthRequest] {
    override def invokeBlock[A](request: AuthRequest[A], block: (AuthRequest[A]) => Future[Result]): Future[Result] =
      block(request).transform(identity, ex => {
        logger.info("Authentication exception", ex)
        AuthenticatedException(request.user, ex)
      })
  }

  def authenticated(onUnauthenticated: RequestHeader => Result = unauthenticated): ActionBuilder[AuthRequest] = {
    new AuthenticatedBuilder(authenticatedUserFor, onUnauthenticated) andThen authenticatedExceptionHandler
  }

}
