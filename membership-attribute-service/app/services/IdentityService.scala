package services

import com.squareup.okhttp._
import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json.{Json, __}
import play.api.libs.concurrent.Execution.Implicits._
import java.net.URLEncoder.encode
import IdentityService._

import scala.concurrent.Future

object IdentityService {

  case class IdentityId(get: String)
  val json = MediaType.parse("application/json; charset=utf-8")

  trait IdentityConfig {
    def token: String
    def url: String
  }

  def authRequest(config: IdentityConfig) =
    new Request.Builder().addHeader("Authorization", s"Bearer ${config.token}")

  def userRequest(config: IdentityConfig)(email: String) =
    authRequest(config).url(config.url + s"/user?emailAddress=${encode(email, "UTF-8")}")

  def parseUserResponse(response: ResponseBody): Option[IdentityId] = {
    implicit val idReads = (__ \ "user" \ "id").read[String].map(IdentityId)
    Json.fromJson[IdentityId](Json.parse(response.string())).asOpt
  }

  def marketingRequest(config: IdentityConfig)(id: IdentityId, prefs: Boolean) = {
    val jsonPayload = Json.obj("statusFields" -> Json.obj("receiveGnmMarketing" -> prefs)).toString()
    val body = RequestBody.create(json, jsonPayload.toString)
    authRequest(config).method("POST", body)
      .url(config.url + s"/user/${id.get}")
  }

  def parseMarketingResponse(response: ResponseBody): Boolean =
    Json.fromJson(Json.parse(response.string()))((__ \ "status").read[String].map(_ == "ok"))
      .getOrElse(throw new IllegalStateException("Bad response from identity"))
}

class IdentityService(state: IdentityConfig, client: OkHttpClient) extends LazyLogging {

  def setMarketingPreference(id: IdentityId, pref: Boolean) = Future {
    parseUserResponse(client.newCall(marketingRequest(state)(id, pref).build()).execute().body())
  }

  def user(email: String) = Future {
    parseUserResponse(client.newCall(userRequest(state)(email).build()).execute().body())
  }
}