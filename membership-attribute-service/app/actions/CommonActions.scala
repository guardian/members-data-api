package actions
import akka.stream.Materializer
import components.{TouchpointBackends, TouchpointComponents}
import controllers.NoCache
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}

class CommonActions(touchpointBackends: TouchpointBackends, bodyParser: BodyParser[AnyContent])(implicit ex: ExecutionContext, mat:Materializer) {
  def noCache(result: Result): Result = NoCache(result)

  val NoCacheAction = resultModifier(noCache)
  val BackendFromCookieAction = NoCacheAction andThen new WithBackendFromCookieAction(touchpointBackends)

  private def resultModifier(f: Result => Result) = new ActionBuilder[Request, AnyContent] {
    override val parser = bodyParser
    override val executionContext = ex
    def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = block(request).map(f)
  }
}

class BackendRequest[A](val touchpoint: TouchpointComponents, request: Request[A]) extends WrappedRequest[A](request)
