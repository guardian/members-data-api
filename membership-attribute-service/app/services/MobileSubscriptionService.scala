package services

import configuration.Config
import models.MobileSubscriptionStatus
import com.github.nscala_time.time.OrderingImplicits._
import play.api.libs.json.{JsError, JsSuccess}
import play.api.libs.ws.WSClient
import scalaz.{-\/, \/, \/-}

import scala.concurrent.{ExecutionContext, Future}

trait MobileSubscriptionService {

  def getSubscriptionStatusForUser(identityId: String): Future[\/[String, Option[MobileSubscriptionStatus]]]

}

class MobileSubscriptionServiceImpl(wsClient: WSClient)(implicit ec: ExecutionContext) extends MobileSubscriptionService {

  private val subscriptionURL = Config.stage match {
    case "PROD" => "https://mobile-purchases.mobile-aws.guardianapis.com/"
    case _ => "https://mobile-purchases.mobile-aws.code.dev-guardianapis.com/"
  }

  override def getSubscriptionStatusForUser(identityId: String): Future[String \/ Option[MobileSubscriptionStatus]] = {
    val response = wsClient.url(s"$subscriptionURL/user/subscriptions/$identityId")
      .withHttpHeaders("Authorization" -> s"Bearer ${Config.Mobile.subscriptionApiKey}")
      .get()

    response.map { resp =>
      if (resp.status != 200) {
        \/.left(s"Unable to fetch the mobile subscription status for $identityId, got HTTP ${resp.status} ${resp.statusText}")
      } else {
        val parsedSubs = (resp.json \ "subscriptions")
          .validate[List[MobileSubscriptionStatus]]

        parsedSubs match {
          case JsError(errors) => -\/(s"Unable to parse mobile subscription response: $errors")
          case JsSuccess(subs, _) =>
            val mostRecentValidSub = subs.filter(_.valid).sortBy(_.endDate).lastOption
            val mostRecentInvalidSub = subs.filterNot(_.valid).sortBy(_.endDate).lastOption
            \/-(mostRecentValidSub.orElse(mostRecentInvalidSub))
        }
      }
    }
  }
}
