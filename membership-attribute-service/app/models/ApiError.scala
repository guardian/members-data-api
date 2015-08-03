package models

import models.ApiResponse._
import play.api.libs.json._
import play.api.mvc.Result

case class ApiError(message: String, friendlyMessage: String,
                    statusCode: Int, context: Option[String] = None)

object ApiError {
  implicit val format = Json.format[ApiError]
  def unexpected(message: String) = ApiError(message, "Unexpected error", 500)
}

case class ApiErrors(errors: List[ApiError]) {
  def statusCode = errors.map(_.statusCode).max

  def toResult: Result = Status(statusCode) {
    JsObject(Seq(
      "status" -> JsString("error"),
      "statusCode" -> JsNumber(statusCode),
      "errors" -> Json.toJson(errors)
    ))
  }
}

object ApiErrors {
  implicit val format = Json.format[ApiErrors]
}
