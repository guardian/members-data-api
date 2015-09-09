package models

import play.api.libs.json._
import play.api.mvc._

import scala.language.implicitConversions

case class ApiError(message: String, details: String, statusCode: Int)

object ApiError {
  implicit val apiErrorWrites = new Writes[ApiError] {
    override def writes(o: ApiError): JsValue = Json.obj(
      "message" -> o.message,
      "details" -> o.details
    )
  }
  implicit def apiErrorToResult(err: ApiError): Result = {
    Results.Status(err.statusCode)(Json.toJson(err))
  }
}
