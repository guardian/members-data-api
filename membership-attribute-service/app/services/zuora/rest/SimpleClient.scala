package services.zuora.rest

import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.{NoOpZuoraMetrics, ZuoraMetrics}
import com.gu.okhttp.RequestRunners.FutureHttpClient
import com.gu.zuora.ZuoraRestConfig
import okhttp3.{Response => OKHttpResponse, _}
import play.api.libs.json.{JsValue, Json, Reads, Writes}
import scalaz.syntax.functor.ToFunctorOps
import scalaz.syntax.std.either._
import scalaz.{Functor, \/}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds
import scala.util.{Success, Try}

/** This is the smallest client required to talk to Zuora over REST It just authenticates calls, adds the right URL and lets you send JSON via a
  * particular request method
  */
case class SimpleClient(
    config: ZuoraRestConfig,
    client: FutureHttpClient,
    metrics: ZuoraMetrics = NoOpZuoraMetrics,
)(implicit functor: Functor[Future], ec: ExecutionContext) {
  def authenticated(url: String)(implicit logPrefix: LogPrefix): Request.Builder = {
    metrics.countRequest() // to count total number of request hitting Zuora

    new Request.Builder()
      .addHeader("apiSecretAccessKey", config.password)
      .addHeader("apiAccessKeyId", config.username)
      .url(s"${config.url}/$url")
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

  def get[B](url: String)(implicit r: Reads[B], logPrefix: LogPrefix): Future[String \/ B] =
    client.execute(authenticated(url).get.build).map(parseResponse(_)(r))

  def put[A, B](url: String, in: A)(implicit r: Reads[B], w: Writes[A], logPrefix: LogPrefix): Future[String \/ B] =
    client.execute(authenticated(url).put(body(in)).build).map(parseResponse(_)(r))

  def post[A, B](url: String, in: A)(implicit r: Reads[B], w: Writes[A], logPrefix: LogPrefix): Future[String \/ B] =
    client.execute(authenticated(url).post(body(in)).build).map(parseResponse(_)(r))

}
