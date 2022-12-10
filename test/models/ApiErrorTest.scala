package models

import org.specs2.mutable.Specification
import play.api.libs.json.Json

class ApiErrorTest extends Specification {

  "toResult" should {
    "create a result with the specified http status and a json body" in {

      Json.toJson(ApiError("message", "details", 400)) shouldEqual
        Json.parse("""
          | {
          |   "message": "message",
          |   "details": "details",
          |   "statusCode": 400
          | }
        """.stripMargin)
    }
  }

}
