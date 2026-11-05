package com.gu.zuora.rest

import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.okhttp.RequestRunners.HttpClient
import com.gu.zuora.ZuoraRestConfig
import okhttp3.{FormBody, Request}
import play.api.libs.json.{JsError, JsSuccess, Json, Reads}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

case class ZuoraAuthenticationError(message: String) extends RuntimeException(message)

private case class AccessTokenResponse(access_token: String, expires_in: Long)

class ZuoraAccessTokenProvider(
    config: ZuoraRestConfig,
    client: HttpClient[Future],
    now: () => Instant = () => Instant.now(),
)(implicit executionContext: ExecutionContext)
    extends AccessTokenProvider[Future] {

  private case class CachedAccessToken(value: String, refreshAt: Instant)

  private implicit val accessTokenResponseReads: Reads[AccessTokenResponse] = Json.reads[AccessTokenResponse]
  private var cachedToken: Option[CachedAccessToken] = None
  private var tokenRequest: Option[Future[CachedAccessToken]] = None

  override def get(): Future[String] = synchronized {
    cachedToken.filter(token => now().isBefore(token.refreshAt)) match {
      case Some(token) => Future.successful(token.value)
      case None =>
        tokenRequest
          .getOrElse(startTokenRequest())
          .map(_.value)
    }
  }

  private def startTokenRequest(): Future[CachedAccessToken] = {
    val result = Promise[CachedAccessToken]()
    tokenRequest = Some(result.future)
    fetchAccessToken().onComplete { tokenResult =>
      synchronized {
        tokenResult.foreach(token => cachedToken = Some(token))
        tokenRequest = None
      }
      result.complete(tokenResult)
    }
    result.future
  }

  /** https://developer.zuora.com/v1-api-reference/api/oauth/createtoken */
  private def fetchAccessToken(): Future[CachedAccessToken] = {
    val formBody = new FormBody.Builder()
      .add("grant_type", "client_credentials")
      .add("client_id", config.clientId)
      .add("client_secret", config.clientSecret)
      .build()

    val request = new Request.Builder()
      .url(config.oauthTokenUrl.toString)
      .post(formBody)
      .build()

    client.execute(request)(LogPrefix.noLogPrefix).flatMap { response =>
      val responseBody = response.body.string()
      if (!response.isSuccessful) {
        Future.failed(ZuoraAuthenticationError(s"Zuora OAuth request failed with status ${response.code}"))
      } else {
        Try(Json.parse(responseBody)).toOption match {
          case Some(json) =>
            json.validate[AccessTokenResponse] match {
              case JsSuccess(token, _) if token.access_token.trim.nonEmpty && token.expires_in > 0 =>
                val refreshInSeconds = math.max(1L, token.expires_in - 30L)
                Future.successful(CachedAccessToken(token.access_token, now().plusSeconds(refreshInSeconds)))
              case JsSuccess(_, _) =>
                Future.failed(ZuoraAuthenticationError("Zuora OAuth response contained an invalid access token or expiry"))
              case JsError(_) =>
                Future.failed(ZuoraAuthenticationError("Zuora OAuth response did not match the expected format"))
            }
          case None =>
            Future.failed(ZuoraAuthenticationError("Zuora OAuth response was not valid JSON"))
        }
      }
    }
  }
}
