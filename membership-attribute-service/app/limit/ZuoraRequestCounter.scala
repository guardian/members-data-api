package limit

import java.util.concurrent.atomic.AtomicInteger
import actions.AuthenticatedUserAndBackendRequest
import loghandling.LoggingWithLogstashFields
import okhttp3.{Request, Response}
import play.api.mvc.AnyContent
import scala.concurrent.{ExecutionContext, Future}

object ZuoraRequestCounter extends LoggingWithLogstashFields {
  private val counter = new AtomicInteger()

  def withZuoraRequestCounter(
    runner: (Request) => Future[Response]
  )(implicit ec: ExecutionContext): (Request) => Future[Response] = { request =>
    val inProgress = counter.incrementAndGet()
    logInfoWithCustomFields(s"started request to zuora, now have $inProgress in progress", List("zuora_concurrency_count" -> inProgress))
    val response = runner(request)
    response.onComplete(_ => counter.decrementAndGet())
    response
  }

  private def calculateZuoraConcurrencyLimitPerInstance(implicit request: AuthenticatedUserAndBackendRequest[AnyContent]): Int = {
    val totalConcurrentCallThreshold = request.touchpoint.totalZuoraConcurrentLimitOnSchedule.getTotalZuoraConcurrentLimitTask.get()
    val awsInstanceCount = request.touchpoint.instanceCountOnSchedule.getInstanceCountTask.get()

    val cappedTotalConcurrentCallThreshold =
      if (totalConcurrentCallThreshold > 40) {
        log.error("Total Zuora concurrent requests limit set too high. Capping it to 40...")
        40
      }
      else totalConcurrentCallThreshold

    if (cappedTotalConcurrentCallThreshold <= 0) {
      log.warn("All requests will be served from cache because totalConcurrentCallThreshold = 0")
      0
    } else if (cappedTotalConcurrentCallThreshold < awsInstanceCount) 1
    else cappedTotalConcurrentCallThreshold / awsInstanceCount
  }

  // https://knowledgecenter.zuora.com/BB_Introducing_Z_Business/Policies/Concurrent_Request_Limits
  def isZuoraConcurrentRequestLimitNotReached(implicit request: AuthenticatedUserAndBackendRequest[AnyContent]): Boolean =
    counter.get() < calculateZuoraConcurrencyLimitPerInstance
}
