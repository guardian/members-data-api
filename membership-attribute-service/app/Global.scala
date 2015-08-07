import controllers.{NoCache, Cached}
import filters.{LogRequestsFilter, AddEC2InstanceHeader}
import models.{MembershipAttributes, ApiError, ApiErrors, ApiResponse}
import play.api.Logger
import play.api.mvc.{RequestHeader, Result, WithFilters}
import play.filters.csrf._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Global extends WithFilters(CSRFFilter(), AddEC2InstanceHeader, LogRequestsFilter) {

  private val logger = Logger(this.getClass)

  override def onBadRequest(request: RequestHeader, error: String): Future[Result] = {
    logger.debug(s"Bad request: $request, error: $error")
    ApiResponse {
      ApiResponse.Left[MembershipAttributes](ApiErrors(List(ApiError("Bad Request", error, 400))))
    } map { NoCache(_) }
  }

  override def onHandlerNotFound(request: RequestHeader): Future[Result] = {
    logger.debug(s"Handler not found for request: $request")
    ApiResponse {
      ApiResponse.Left[MembershipAttributes](ApiErrors(List(ApiError("Not Found", s"Handler not found for request: $request", 404))))
    } map { Cached(_) }
  }

  override def onError(request: RequestHeader, ex: Throwable): Future[Result] = {
    logger.error(s"Error handling request request: $request", ex)
    ApiResponse {
      ApiResponse.Left[MembershipAttributes](ApiErrors(List(ApiError("Error", s"Error handling request request: $request", 500))))
    } map { NoCache(_) }
  }
}
