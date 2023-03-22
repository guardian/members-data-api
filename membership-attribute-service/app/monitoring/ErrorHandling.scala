package monitoring
import controllers.{Cached, NoCache}
import filters.AddGuIdentityHeaders
import models.ApiErrors.{badRequest, internalError, notFound}
import play.api._
import play.api.http.DefaultHttpErrorHandler
import play.api.libs.json.Json
import play.api.mvc.Results._
import play.api.mvc._
import play.api.routing.Router
import play.core.SourceMapper
import services.IdentityAuthService
import utils.SanitizedLogging
import utils.Sanitizer.Sanitizer

import scala.concurrent._

class ErrorHandler(
    env: Environment,
    config: Configuration,
    sourceMapper: Option[SourceMapper],
    router: => Option[Router],
    identityAuthService: IdentityAuthService,
    addGuIdentityHeaders: AddGuIdentityHeaders,
)(implicit executionContext: ExecutionContext)
    extends DefaultHttpErrorHandler(env, config, sourceMapper, router)
    with SanitizedLogging {

  override def onClientError(request: RequestHeader, statusCode: Int, message: String = ""): Future[Result] = {
    super.onClientError(request, statusCode, message).map(Cached(_))
  }

  override protected def onNotFound(request: RequestHeader, message: String): Future[Result] = {

    logger.debug(s"Handler not found for request: $request")
    Future.successful(
      Cached(NotFound(Json.toJson(notFound))),
    )
  }

  override protected def onProdServerError(request: RequestHeader, ex: UsefulException): Future[Result] = {
    logError(scrub"Error handling request request: $request", ex)
    addGuIdentityHeaders.fromIdapiIfMissing(request, internalError)
  }
  override protected def onBadRequest(request: RequestHeader, message: String): Future[Result] = {
    logServerError(request, new PlayException("Bad request", message))
    Future.successful(NoCache(BadRequest(Json.toJson(badRequest(message)))))
  }
}
