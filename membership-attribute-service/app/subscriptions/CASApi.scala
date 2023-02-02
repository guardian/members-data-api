package subscriptions

import models.subscription.Subscription.Name
import models.subscription.util.WebServiceHelper
import utils.RequestRunners._
import subscriptions.CAS.Deserializer._
import subscriptions.CAS._
import subscriptions.Quadrant.looksLikeAQuadrantSubscriber
import play.api.libs.json.Json

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}

class CASApi(url: String, client: FutureHttpClient)(implicit ec: ExecutionContext) extends WebServiceHelper[CASResult, CASError] {

  val wsUrl = url
  val httpClient = client

  def check(subscriptionName: Name, password: String, triggersActivation: Boolean)(implicit ec: ExecutionContext): Future[CASResult] = {
    val queryParams = if (triggersActivation) Seq() else Seq(("noActivation", "true"))
    post[CASSuccess](
      "subs",
      Json.obj(
        "appId" -> "membership.theguardian.com",
        "deviceId" -> "",
        "subscriberId" -> subscriptionName.get,
        "password" -> password,
      ),
      queryParams: _*,
    ).recover { case error: CASError =>
      error
    }
  }
}

object Quadrant {
  def looksLikeAQuadrantSubscriber(subscriptionName: Name) =
    subscriptionName.get.matches("""00\d{6}""")
}

class CASService(api: CASApi)(implicit ec: ExecutionContext) {
  def check(subscriptionName: Name, password: String, triggersActivation: Boolean = true): Future[CASResult] =
    api.check(subscriptionName, password, triggersActivation)(ec) map {
      case result: CASSuccess =>
        result
      case _ if looksLikeAQuadrantSubscriber(subscriptionName) =>
        CASSuccess("sub", Some("quadrant"), LocalDate.now.plusDays(1).toString, Some("Unknown"), "Unknown")
      case result =>
        result
    }
}
