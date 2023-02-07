package services.zuora.soap

import _root_.models.subscription.Subscription.Feature.Code
import _root_.models.subscription.util.FutureRetry._
import akka.actor.ActorSystem
import com.gu.monitoring.{NoOpZuoraMetrics, ZuoraMetrics}
import com.gu.okhttp.RequestRunners.FutureHttpClient
import com.gu.zuora.ZuoraSoapConfig
import monitoring.SafeLogger
import services.zuora.soap.models.Queries.Feature
import services.zuora.soap.Readers._

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure
import monitoring.SafeLogger._

class ClientWithFeatureSupplier(
    featureCodes: Set[Code],
    apiConfig: ZuoraSoapConfig,
    httpClient: FutureHttpClient,
    extendedHttpClient: FutureHttpClient,
    metrics: ZuoraMetrics = NoOpZuoraMetrics,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends Client(apiConfig, httpClient, extendedHttpClient, metrics) {

  // FIXME: This is strange and should be removed. Seems to be used only by membership-frontend which is essentially a dead product
  val featuresSupplier = new AtomicReference[Future[Seq[Feature]]](null)
  actorSystem.scheduler.schedule(0.seconds, 30.minutes) {
    retry(getFeatures)(ec, actorSystem.scheduler)
  }
  private def getFeatures: Future[Seq[Feature]] = {
    query[Feature](SimpleFilter("Status", "Active")).map { features =>
      val diff = featureCodes &~ features.map(_.code).toSet
      if (diff.nonEmpty) {
        SafeLogger.error(
          scrub"Zuora ${apiConfig.envName} is missing the following product features: ${diff.mkString(", ")}. Please update configuration ASAP!",
        )
      }
      featuresSupplier.set(Future.successful(features))
      logger.info("Successfully refreshed features")
      features
    }
  } andThen { case Failure(e) => logger.error("Failed to refresh features", e) }
}
