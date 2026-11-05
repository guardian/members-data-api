package com.gu.zuora.rest

import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.{NoOpZuoraMetrics, ZuoraMetrics}
import com.gu.okhttp.RequestRunners.HttpClient
import com.gu.zuora.ZuoraRestConfig
import okhttp3.{Response => OKHttpResponse, _}
import play.api.libs.json.{JsValue, Json, Reads, Writes}
import scalaz.syntax.std.either._
import scalaz.syntax.monad._
import scalaz.{Monad, \/}

import scala.util.{Success, Try}

/** This is the smallest client required to talk to Zuora over REST It just authenticates calls, adds the right URL and lets you send JSON via a
  * particular request method
  */
case class SimpleClient[M[_]: Monad](
    config: ZuoraRestConfig,
    accessTokenProvider: AccessTokenProvider[M],
    client: HttpClient[M],
    metrics: ZuoraMetrics = NoOpZuoraMetrics,
) {
  private def authenticated(url: String, accessToken: String)(implicit logPrefix: LogPrefix): Request.Builder = {
    metrics.countRequest() // to count total number of request hitting Zuora

    new Request.Builder()
      .addHeader("Authorization", s"Bearer $accessToken")
      .url(s"${config.url}/$url")
  }

  private def execute(url: String)(buildRequest: Request.Builder => Request)(implicit logPrefix: LogPrefix): M[OKHttpResponse] =
    accessTokenProvider.get().flatMap { accessToken =>
      client.execute(buildRequest(authenticated(url, accessToken)))
    }

  def isSuccess(statusCode: Int) = statusCode >= 200 && statusCode < 300

  def parseJson[B](in: OKHttpResponse): \/[String, JsValue] = {
    if (isSuccess(in.code)) {
      (for {
        body <- Try(in.body().string)
        json <- Try(Json.parse(body))
      } yield json) match {
        case Success(v) => \/.r[String](v)
        case scala.util.Failure(e) => \/.l[JsValue](e.toString)
      }
    } else {
      val bodyStr = Try(in.body.string).toOption
      \/.l[JsValue](s"response with status ${in.code}, body:$bodyStr")
    }
  }

  def parseResponse[B](in: OKHttpResponse)(implicit r: Reads[B]): String \/ B = {
    parseJson(in).flatMap { jsValue =>
      r.reads(jsValue)
        .asEither
        .toDisjunction
        .leftMap(error => s"json was well formed but not matching the reader: $error, json was << ${Json.prettyPrint(jsValue)} >>")
    }
  }

  def body[A](in: A)(implicit w: Writes[A]): RequestBody = jsonBody(w.writes(in))

  def jsonBody(in: JsValue): RequestBody = RequestBody.create(MediaType.parse("application/json"), in.toString())

  def get[B](url: String)(implicit r: Reads[B], logPrefix: LogPrefix): M[String \/ B] =
    execute(url)(_.get.build).map(parseResponse(_)(r))

  def getJson(url: String)(implicit logPrefix: LogPrefix): M[String \/ JsValue] =
    execute(url)(_.get.build).map(parseJson(_))

  def put[A, B](url: String, in: A)(implicit r: Reads[B], w: Writes[A], logPrefix: LogPrefix): M[String \/ B] =
    execute(url)(_.put(body(in)).build).map(parseResponse(_)(r))

  def post[A, B](url: String, in: A)(implicit r: Reads[B], w: Writes[A], logPrefix: LogPrefix): M[String \/ B] =
    execute(url)(_.post(body(in)).build).map(parseResponse(_)(r))

}
