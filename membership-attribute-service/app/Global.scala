
import configuration.Config
import filters.AddGuIdentityHeaders
import loghandling.Logstash
import models.ApiErrors._
import monitoring.SentryLogging
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{RequestHeader, Result}
import play.api.{Application, GlobalSettings, Logger}

import scala.concurrent.Future


object Global extends GlobalSettings {

  private val logger = Logger(this.getClass)

  override def onStart(app: Application) {
    SentryLogging.init()
    Logstash.init(Config)
  }

  override def onBadRequest(request: RequestHeader, error: String): Future[Result] = {
    logger.debug(s"Bad request: $request, error: $error")
    Future { badRequest(error) }
  }

  override def onHandlerNotFound(request: RequestHeader): Future[Result] = {
    logger.debug(s"Handler not found for request: $request")
    Future { notFound }
  }

  override def onError(request: RequestHeader, ex: Throwable): Future[Result] = {
    logger.error(s"Error handling request request: $request", ex)
    Future { AddGuIdentityHeaders.headersFor(request, internalError) }
  }
}
