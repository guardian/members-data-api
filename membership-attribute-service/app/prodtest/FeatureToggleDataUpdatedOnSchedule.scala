package prodtest

import akka.actor.ActorSystem
import com.gu.memsub.util.ScheduledTask
import com.typesafe.scalalogging.LazyLogging
import models.ZuoraLookupFeatureData
import services.ScanamoFeatureToggleService

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scalaz.{-\/, \/-}

class FeatureToggleDataUpdatedOnSchedule(featureToggleService: ScanamoFeatureToggleService, stage: String)(implicit ec: ExecutionContext, system: ActorSystem) extends LazyLogging {

  private val updateZuoraLookupFeatureDataTask: ScheduledTask[ZuoraLookupFeatureData] = {
    val featureName = "UpdateAttributesFromZuoraLookup"
    val defaultFeatureValues = ZuoraLookupFeatureData(TrafficPercentage = 0, ConcurrentZuoraCallThreshold = 10)

    ScheduledTask[ZuoraLookupFeatureData](featureName, defaultFeatureValues, 0.seconds, 30.seconds) {
      featureToggleService.get("AttributesFromZuoraLookup") map { result =>
        result match {
          case \/-(feature) =>
            val percentage = feature.TrafficPercentage.getOrElse(defaultFeatureValues.TrafficPercentage)
            val threshold = feature.ConcurrentZuoraCallThreshold.getOrElse(defaultFeatureValues.ConcurrentZuoraCallThreshold)
            logger.info(s"$featureName scheduled task set percentage to $percentage and zuora concurrency threshold to $threshold in $stage")
            ZuoraLookupFeatureData(percentage, threshold)
          case -\/(e) =>
            logger.warn(s"Tried to update the percentage of traffic for $featureName and the zuora concurrency threshold, but that feature was not " +
              s"found in the table. Setting traffic to ${defaultFeatureValues.TrafficPercentage}% and concurrency threshold to " +
              s"${defaultFeatureValues.ConcurrentZuoraCallThreshold} in $stage. Error: $e")
            defaultFeatureValues
        }
      }
    }
  }

  updateZuoraLookupFeatureDataTask.start()

  def getZuoraLookupFeatureDataTask = updateZuoraLookupFeatureDataTask
}