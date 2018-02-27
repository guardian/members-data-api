package services

import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json.{JsValue, Json, __}
import java.net.URLEncoder.encode

import IdentityService._
import com.gu.okhttp.RequestRunners
import okhttp3.{MediaType, Request, RequestBody, ResponseBody}

import scala.concurrent.{ExecutionContext, Future}

object IdentityService {

  case class IdentityId(get: String)
  val json = MediaType.parse("application/json; charset=utf-8")

  trait IdentityConfig {
    def token: String
    def url: String
  }

  def authRequest(config: IdentityConfig) =
    new Request.Builder().addHeader("Authorization", s"Bearer ${config.token}")

  def userIdRequest(config: IdentityConfig)(userId: IdentityId) =
    authRequest(config).url(config.url + s"/user/${userId.get}")

  def parseUserIdResponse(response: ResponseBody): JsValue = {
    Json.parse(response.string())
  }

}

class IdentityService(state: IdentityConfig, client: RequestRunners.FutureHttpClient)(implicit executionContext: ExecutionContext) extends LazyLogging {

  def user(id: IdentityId) = {
    client(userIdRequest(state)(id).build).map { response =>
      parseUserIdResponse(response.body)
    }

  }
}