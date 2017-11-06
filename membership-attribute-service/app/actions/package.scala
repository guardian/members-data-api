import components.TouchpointComponents
import controllers.NoCache
import play.api.mvc._

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

package object actions {
  def noCache(result: Result): Result = NoCache(result)

  val NoCacheAction = resultModifier(noCache)

  val BackendFromCookieAction = NoCacheAction andThen WithBackendFromCookieAction

  class BackendRequest[A](val touchpoint: TouchpointComponents, request: Request[A]) extends WrappedRequest[A](request)

  private def resultModifier(f: Result => Result) = new ActionBuilder[Request] {
    def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = block(request).map(f)
  }
}
