package services.identity

import models.subscription.util.WebServiceHelper
import okhttp3.{Headers, Request}
import play.api.libs.json._
import utils.RequestRunners.FutureHttpClient

import scala.concurrent.{ExecutionContext, Future}

sealed trait IdapiResponseObject
case class IdapiError(
    status: String,
) extends Throwable

sealed trait RedirectAdviceStatus
case object SignedInRecently extends RedirectAdviceStatus
case object SignedInNotRecently extends RedirectAdviceStatus
case object NotSignedIn extends RedirectAdviceStatus
case object UnknownSignInStatus extends RedirectAdviceStatus
@Deprecated case object OkRedirectStatus extends RedirectAdviceStatus

case class RedirectAdviceObject(
    url: String,
)
case class RedirectAdviceResponse(
    signInStatus: RedirectAdviceStatus,
    userId: Option[String],
    displayName: Option[String],
    emailValidated: Option[Boolean],
    redirect: Option[RedirectAdviceObject],
) extends IdapiResponseObject

object IdapiService {
  val HeaderNameCookie = "Cookie"
  val HeaderNameIdapiForwardedScope = "X-GU-ID-FORWARDED-SCOPE"
}

class IdapiService(apiConfig: IdapiConfig, client: FutureHttpClient)(implicit ec: ExecutionContext)
    extends WebServiceHelper[IdapiResponseObject, IdapiError] {

  val wsUrl = apiConfig.url
  val httpClient: FutureHttpClient = client

  implicit val readsIdapiError = Json.reads[IdapiError]

  override def wsPreExecute(req: Request.Builder): Request.Builder = {
    req.addHeader("X-GU-ID-Client-Access-Token", s"Bearer ${apiConfig.token}")
  }

  object RedirectAdvice {

    private implicit val readsRedirectStatus: Reads[RedirectAdviceStatus] = __.read[String].map {
      case "signedInRecently" => SignedInRecently
      case "signedInNotRecently" => SignedInNotRecently
      case "notSignedIn" => NotSignedIn
      case "ok" => OkRedirectStatus
      case _ => UnknownSignInStatus
    }

    private implicit val readsRedirectObject: Reads[RedirectAdviceObject] = Json.reads[RedirectAdviceObject]

    private implicit val readsRedirectResponse: Reads[RedirectAdviceResponse] = Json.reads[RedirectAdviceResponse]

    def getRedirectAdvice(cookieValue: String, scope: Option[String] = None): Future[RedirectAdviceResponse] =
      get[RedirectAdviceResponse](
        "auth/redirect",
        Headers.of(
          IdapiService.HeaderNameCookie,
          cookieValue,
          IdapiService.HeaderNameIdapiForwardedScope,
          scope.getOrElse(""),
        ),
      )
  }

}
