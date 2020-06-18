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
  private val defaultTotalZuoraConcurrencyLimit = 6

  val getTotalZuoraConcurrentLimitTask: ScheduledTask[Int] =
    ScheduledTask[Int]("AttributesFromZuoraLookup", defaultTotalZuoraConcurrencyLimit, 0.seconds, 30.seconds) {
      featureToggleService.get("AttributesFromZuoraLookup") map {
        case Right(feature) =>
          feature.ConcurrentZuoraCallThreshold.getOrElse(defaultTotalZuoraConcurrencyLimit)
        case Left(e) =>
          logger.error(s"Failed to fetch ConcurrentZuoraCallThreshold from DynamoDB. Failing to default value $defaultTotalZuoraConcurrencyLimit", e)
          defaultTotalZuoraConcurrencyLimit
      }
    }

  getTotalZuoraConcurrentLimitTask.start()
}