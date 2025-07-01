package monitoring
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import controllers.{Cached, NoCache}
import filters.AddGuIdentityHeaders
import filters.AddGuIdentityHeaders.{identityHeaderNames, xGuIdentityIdHeaderName}
import models.ApiErrors.{badRequest, internalError, notFound}
import play.api._
import play.api.http.DefaultHttpErrorHandler
import play.api.libs.json.Json
import play.api.mvc.Results._
import play.api.mvc._
import play.api.routing.Router
import play.core.SourceMapper
import services.IdentityAuthService

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
    with SafeLogging {

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
    // log first to make sure it's not lost
    logger.errorNoPrefix(scrub"Error handling request request: $request", ex)
    val result = addGuIdentityHeaders.fromIdapiIfMissing(request, internalError)
    result.foreach { result: Result =>
      val identityId = result.header.headers.get(xGuIdentityIdHeaderName)
      val prefix = identityId.getOrElse("no-identity-id")
      implicit val logPrefix: LogPrefix = LogPrefix(prefix)
      // now log with the identity id so it appears in searches
      logger.error(scrub"Error handling request request: $request", ex)
    }
    result
  }

  override protected def onBadRequest(request: RequestHeader, message: String): Future[Result] = {
    logServerError(request, new PlayException("Bad request", message))
    Future.successful(NoCache(BadRequest(Json.toJson(badRequest(message)))))
  }

}
