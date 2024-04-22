package com.gu.zuora.soap

import com.gu.memsub.Subscription.Feature.Code
import com.gu.memsub.util.FutureRetry._
import com.gu.monitoring.{NoOpZuoraMetrics, ZuoraMetrics}
import com.gu.okhttp.RequestRunners._
import com.gu.zuora.ZuoraSoapConfig
import com.gu.zuora.soap.Readers._
import com.gu.zuora.soap.models.Queries.Feature
import org.apache.pekko.actor.ActorSystem

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

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
        logger.error(
          scrub"Zuora ${apiConfig.envName} is missing the following product features: ${diff.mkString(", ")}. Please update configuration ASAP!",
        )
      }
      featuresSupplier.set(Future.successful(features))
      logger.info("Successfully refreshed features")
      features
    }
  } andThen { case Failure(e) => logger.error(scrub"Failed to refresh features", e) }
}
