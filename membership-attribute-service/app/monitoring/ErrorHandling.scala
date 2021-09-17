package monitoring
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import com.typesafe.scalalogging.LazyLogging
import controllers.{Cached, NoCache}
import filters.AddGuIdentityHeaders
import play.api._
import play.api.http.DefaultHttpErrorHandler
import play.api.mvc.Results._
import play.api.mvc._
import play.api.routing.Router
import play.core.SourceMapper
import models.ApiErrors.{badRequest, internalError, notFound}
import play.api.libs.json.Json
import services.IdentityAuthService

import scala.concurrent._

class ErrorHandler(
    env: Environment,
    config: Configuration,
    sourceMapper: Option[SourceMapper],
    router: => Option[Router],
    identityAuthService: IdentityAuthService
)(implicit executionContext: ExecutionContext)
    extends DefaultHttpErrorHandler(env, config, sourceMapper, router)
    with LazyLogging {

  override def onClientError(request: RequestHeader, statusCode: Int, message: String = ""): Future[Result] = {
    super.onClientError(request, statusCode, message).map(Cached(_))
  }

  override protected def onNotFound(request: RequestHeader, message: String): Future[Result] = {

    logger.debug(s"Handler not found for request: $request")
    Future.successful(
      Cached(NotFound(Json.toJson(notFound)))
    )
  }

  override protected def onProdServerError(request: RequestHeader, ex: UsefulException): Future[Result] = {
    SafeLogger.error(scrub"Error handling request request: $request", ex)
    AddGuIdentityHeaders.headersFor(request, internalError, identityAuthService)
  }
  override protected def onBadRequest(request: RequestHeader, message: String): Future[Result] = {
    logServerError(request, new PlayException("Bad request", message))
    Future.successful(NoCache(BadRequest(Json.toJson(badRequest(message)))))
  }
}
