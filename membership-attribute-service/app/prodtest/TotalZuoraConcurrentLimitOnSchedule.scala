package prodtest

import akka.actor.ActorSystem
import com.gu.memsub.util.ScheduledTask
import com.typesafe.scalalogging.LazyLogging
import services.FeatureToggleService
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class TotalZuoraConcurrentLimitOnSchedule(
  featureToggleService: FeatureToggleService
)(implicit ec: ExecutionContext, system: ActorSystem) extends LazyLogging {
  private val defaultTotalZouraConcurrencyLimit = 6

  private val _getTotalZuoraConcurrentLimitTask: ScheduledTask[Int] = {
    ScheduledTask[Int]("AttributesFromZuoraLookup", defaultTotalZouraConcurrencyLimit, 0.seconds, 30.seconds) {
      featureToggleService.get("AttributesFromZuoraLookup") map {
        case Right(feature) =>
          feature.ConcurrentZuoraCallThreshold.getOrElse(defaultTotalZouraConcurrencyLimit)
        case Left(e) =>
          logger.error(s"Failed to fetch ConcurrentZuoraCallThreshold from DynamoDB. Failing to default value $defaultTotalZouraConcurrencyLimit", e)
          defaultTotalZouraConcurrencyLimit
      }
    }
  }

  _getTotalZuoraConcurrentLimitTask.start()

  def getTotalZuoraConcurrentLimitTask: ScheduledTask[Int] = _getTotalZuoraConcurrentLimitTask
}