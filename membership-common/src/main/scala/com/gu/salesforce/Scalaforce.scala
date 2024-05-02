package com.gu.salesforce

import com.gu.memsub.util.FutureRetry._
import com.gu.memsub.util.Timing
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.{SafeLogging, SalesforceMetrics}
import com.gu.okhttp.RequestRunners.FutureHttpClient
import okhttp3._
import org.apache.pekko.actor.{Cancellable, Scheduler}
import play.api.libs.json._
import scalaz.std.scalaFuture._
import scalaz.{-\/, Monad, \/, \/-}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class SFContactRecord(Id: String, AccountId: String, IdentityID__c: Option[String])

case class SFContactId(get: String)

case class Authentication(access_token: String, instance_url: String)

case class ScalaforceError(s: String) extends Throwable {
  override def getMessage: String = s
}

/** Uses the Salesforce Username-Password Flow to get access tokens.
  *
  * https://help.salesforce.com/apex/HTViewHelpDoc?id=remoteaccess_oauth_username_password_flow.htm
  * https://www.salesforce.com/us/developer/docs/api_rest/Content/intro_understanding_username_password_oauth_flow.htm
  */

abstract class Scalaforce(implicit ec: ExecutionContext) extends SafeLogging {
  val stage: String
  val application: String
  val sfConfig: SalesforceConfig
  val httpClient: FutureHttpClient
  val sfScheduler: Scheduler

  def isAuthenticated: Boolean = Option(periodicAuth.get()).isDefined

  protected val periodicAuth = new AtomicReference[Option[Authentication]](None)

  object Status {
    val OK = 200
    val NOT_FOUND = 404
  }

  lazy val metrics = new SalesforceMetrics(stage, application)

  protected def issueRequest(req: Request)(implicit logPrefix: LogPrefix): Future[Response] = {
    val requestLog = s"${req.method()} to ${req.url()}"
    metrics.recordRequest()
    httpClient.execute(req).map { response =>
      metrics.recordResponse(response.code(), req.method())
      if (!response.isSuccessful && (response.code() != Status.NOT_FOUND)) {
        logger.warn(
          s"Unexpected response from Salesforce. We attempted to $requestLog." +
            s" Received response code: ${response.code()}| response body: ${response.peekBody(Long.MaxValue).string()}",
        )
      }
      response
    }
  }

  private def urlAuth: Future[String => Request.Builder] = {
    val maybeAuth = periodicAuth.get
    val futureAuth = maybeAuth.map(Future.successful).getOrElse(authorize)
    futureAuth.map { auth => (endpoint: String) =>
      {
        new Request.Builder()
          .url(s"${auth.instance_url}/$endpoint")
          .addHeader("Authorization", s"Bearer ${auth.access_token}")
      }
    }
  }

  private def get(endpoint: String)(implicit logPrefix: LogPrefix): Future[Response] = urlAuth.flatMap { url =>
    issueRequest(url(endpoint).get().build())
  }

  private def post(endpoint: String, updateData: JsValue)(implicit logPrefix: LogPrefix): Future[Response] = {
    val mediaType = MediaType.parse("application/json; charset=utf-8")
    val body = RequestBody.create(Json.stringify(updateData), mediaType)
    urlAuth.flatMap { url =>
      issueRequest(url(endpoint).post(body).build())
    }
  }

  private def patch(endpoint: String, updateData: JsValue)(implicit logPrefix: LogPrefix): Future[Response] = {
    val mediaType = MediaType.parse("application/json; charset=utf-8")
    val body = RequestBody.create(Json.stringify(updateData), mediaType)
    urlAuth.flatMap { url =>
      issueRequest(url(endpoint).patch(body).build())
    }
  }

  private def jsonParse(response: String): JsValue = Json.parse(response)

  object Contact {
    def read(key: String, id: String)(implicit logPrefix: LogPrefix): Future[\/[String, Option[JsValue]]] = {
      val path = s"services/data/v57.0/sobjects/Contact/$key/$id"
      Timing
        .record(metrics, "Read Contact") {
          get(path)
        }
        .map { response =>
          val bodyString = response.body().string() // out here to make sure connection is closed in all cases
          response.code() match {
            case Status.OK => \/-(Some(jsonParse(bodyString)))
            case Status.NOT_FOUND => \/-(None)
            case code => -\/(s"SF003: Salesforce returned code $code for Contact read $key $id")
          }
        }
    }

    private def update(id: SFContactId, json: JsValue)(implicit logPrefix: LogPrefix): Future[Unit] = Timing
      .record(metrics, "Update Contact") {
        patch(s"services/data/v54.0/sobjects/Contact/${id.get}", json)
      }
      .flatMap { r =>
        val output = r.body().string()
        r.body().close()
        Monad[Future].unlessM(r.code == 204)(Future.failed(new Exception(s"Bad code for update ${r.code}: $output")))
      }

    def update(id: SFContactId, newFields: Map[String, String])(implicit logPrefix: LogPrefix): Future[Unit] = {
      val fields = newFields map { case (name, value) =>
        name -> Json.toJsFieldJsValueWrapper(value)
      }
      update(id, Json.obj(fields.toList: _*))
    }
  }

  // 15min -> 96 request/day. Failed auth will not override previous access_token.
  def startAuth(): Cancellable = sfScheduler.scheduleAtFixedRate(0.seconds, 15.minutes)(() => fetchAndStoreAuth())

  private def fetchAndStoreAuth() = authorize.onComplete {
    case Success(auth) =>
      periodicAuth.set(Some(auth))
    case Failure(ex) =>
      logger.errorNoPrefix(scrub"Failed Salesforce authentication $stage", ex)
      metrics.recordAuthenticationError()
  }

  protected def authorize: Future[Authentication] = {
    retry {
      logger.infoNoPrefix(s"Trying to authenticate with Salesforce $stage...")

      val formBody = new FormBody.Builder()
        .add("client_id", sfConfig.key)
        .add("client_secret", sfConfig.secret)
        .add("username", sfConfig.username)
        .add("password", sfConfig.password + sfConfig.token)
        .add("grant_type", "password")
        .build()

      val request = new Request.Builder().url(s"${sfConfig.url}/services/oauth2/token").post(formBody).build()

      issueRequest(request)(LogPrefix.noLogPrefix).map { response =>
        implicit val reads = Json.reads[Authentication]
        val responseBody = jsonParse(response.body().string())
        responseBody.validate[Authentication] match {
          case JsSuccess(result, _) =>
            logger.infoNoPrefix(s"Successful Salesforce $stage authentication.")
            result
          case _ =>
            throw ScalaforceError(s"Failed Salesforce $stage authentication: CODE = ${response.code()}; Response = $responseBody")
        }
      }
    }(ec, sfScheduler)
  }

}
