package services

import com.github.nscala_time.time.OrderingImplicits._
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.typesafe.config.Config
import models.MobileSubscriptionStatus
import play.api.libs.json.{JsError, JsSuccess}
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}

trait MobileSubscriptionService {

  def getSubscriptionStatusForUser(identityId: String)(implicit logPrefix: LogPrefix): Future[Either[String, Option[MobileSubscriptionStatus]]]

}

class MobileSubscriptionServiceImpl(wsClient: WSClient, config: Config)(implicit ec: ExecutionContext)
    extends MobileSubscriptionService
    with SafeLogging {
  val mobileSubscriptionApiKey = config.getString("mobile.subscription.apiKey")

  private val subscriptionURL = config.getString("stage") match {
    case "PROD" => "https://mobile-purchases.mobile-aws.guardianapis.com"
    case _ => "https://mobile-purchases.mobile-aws.code.dev-guardianapis.com"
  }

  override def getSubscriptionStatusForUser(
      identityId: String,
  )(implicit logPrefix: LogPrefix): Future[Either[String, Option[MobileSubscriptionStatus]]] = {
    val response = wsClient
      .url(s"$subscriptionURL/user/subscriptions/$identityId")
      .withHttpHeaders("Authorization" -> s"Bearer $mobileSubscriptionApiKey")
      .get()

    response.map { resp =>
      if (resp.status != 200) {
        Left(s"Unable to fetch the mobile subscription status for $identityId, got HTTP ${resp.status} ${resp.statusText}")
      } else {
        val parsedSubs = (resp.json \ "subscriptions")
          .validate[List[MobileSubscriptionStatus]]

        parsedSubs match {
          case JsError(errors) => Left(s"Unable to parse mobile subscription response: $errors")
          case JsSuccess(subs, _) =>
            logger.info(s"Successfully retrieved ${subs.size} mobile subscriptions for $identityId")
            val mostRecentValidSub = subs.filter(_.valid).sortBy(_.to).lastOption
            val mostRecentInvalidSub = subs.filterNot(_.valid).sortBy(_.to).lastOption
            val result = mostRecentValidSub.orElse(mostRecentInvalidSub)
            logger.info(s"Mobile subscription for $identityId is $result")
            Right(result)
        }
      }
    }
  }
}
