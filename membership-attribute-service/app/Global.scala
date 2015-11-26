import filters.{AddEC2InstanceHeader, LogRequestsFilter}
import models.ApiErrors._
import monitoring.SentryLogging
import play.api.{Application, Logger}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{RequestHeader, Result, WithFilters}
import play.filters.csrf._
import scala.concurrent.Future

object Global extends WithFilters(CSRFFilter(), AddEC2InstanceHeader, LogRequestsFilter) {

  private val logger = Logger(this.getClass)

  override def onStart(app: Application) {
    SentryLogging.init()
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
    Future { internalError }
  }
}
