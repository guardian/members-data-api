package com.gu.zuora.soap

import com.gu.monitoring.{NoOpZuoraMetrics, ZuoraMetrics}
import com.gu.okhttp.RequestRunners._
import com.gu.zuora.ZuoraSoapConfig
import org.apache.pekko.actor.ActorSystem

import scala.concurrent.ExecutionContext

class ClientWithFeatureSupplier(
    apiConfig: ZuoraSoapConfig,
    httpClient: FutureHttpClient,
    extendedHttpClient: FutureHttpClient,
    metrics: ZuoraMetrics = NoOpZuoraMetrics,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends Client(apiConfig, httpClient, extendedHttpClient, metrics)