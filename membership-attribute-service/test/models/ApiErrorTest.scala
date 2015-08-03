package models

import org.specs2.mutable.Specification
import play.api.test.Helpers._

import scala.concurrent.Future

class ApiErrorTest extends Specification {

  "toResult" should {
    "create a result with the specified http status and a json body" in {
      val statusCode = UNAUTHORIZED
      val statusMessage = "error"
      val message = "message"
      val friendlyMessage = "friendlyMessage"

      val apiErrors = ApiErrors(List(ApiError(message, friendlyMessage, statusCode)))
      val result = Future.successful(apiErrors.toResult)
      status(result) shouldEqual statusCode
      val jsonBody = contentAsJson(result)
      (jsonBody \ "status").toOption.map(_.as[String]) shouldEqual Some(statusMessage)
      (jsonBody \ "statusCode").toOption.map(_.as[Int]) shouldEqual Some(statusCode)
      (jsonBody \ "errors" \\ "message").headOption.map(_.as[String]) shouldEqual Some(message)
      (jsonBody \ "errors" \\ "statusCode").headOption.map(_.as[Int]) shouldEqual Some(statusCode)
      (jsonBody \ "errors" \\ "friendlyMessage").headOption.map(_.as[String]) shouldEqual Some(friendlyMessage)
    }
  }

}
