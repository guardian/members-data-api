package prodtest

import akka.actor.ActorSystem
import com.gu.memsub.util.ScheduledTask
import com.typesafe.scalalogging.LazyLogging
import services.ScanamoFeatureToggleService

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class VariablesUpdatedOnSchedule(featureToggleService: ScanamoFeatureToggleService)(implicit ec: ExecutionContext, system: ActorSystem) extends LazyLogging {

  private var percentageTrafficForZuoraLookup = 0

  private val updateZuoraTrafficPercentageTask =
    ScheduledTask[Int]("UpdateAttributesFromZuoraLookupPercentage", 0, 0.seconds, 30.seconds) {
      featureToggleService.get("AttributesFromZuoraLookup").map {
        case Some(feature) =>
          val percentage = feature.TrafficPercentage.getOrElse(0)
          percentageTrafficForZuoraLookup = percentage
          percentage
        case None =>
          logger.info("Tried to update the percentage of traffic to do lookups via zuora, but that feature was not " +
            "found in the table. Setting traffic to 0%")
          0
      }

    }
  updateZuoraTrafficPercentageTask.start()

  def getPercentageTrafficForZuoraLookup = percentageTrafficForZuoraLookup
}