package framework

import play.api.Logger
import play.api.http.HttpErrorHandler
import play.api.mvc.{Result, RequestHeader}
import play.api.libs.concurrent.Execution.Implicits._
import models.ApiErrors._

import scala.concurrent.Future

class JsonHttpErrorHandler extends HttpErrorHandler {
  override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] =  {
    if(statusCode == play.api.http.Status.NOT_FOUND) {
      Logger.debug(s"Handler not found for request: $request")
      Future { notFound }
    } else {
      Logger.debug(s"Bad request: $request, error: $message")
      Future { badRequest(message) }
    }

  }
  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
    Logger.error(s"Error handling request request: $request", exception)
    Future { internalError }
  }
}
