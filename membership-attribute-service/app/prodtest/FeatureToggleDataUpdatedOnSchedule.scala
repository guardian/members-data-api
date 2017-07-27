package prodtest

import akka.actor.ActorSystem
import com.gu.memsub.util.ScheduledTask
import com.typesafe.scalalogging.LazyLogging
import services.ScanamoFeatureToggleService

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

class FeatureToggleDataUpdatedOnSchedule(featureToggleService: ScanamoFeatureToggleService)(implicit ec: ExecutionContext, system: ActorSystem) extends LazyLogging {

  private val updateZuoraTrafficPercentageTask: ScheduledTask[Int] =
    ScheduledTask[Int]("UpdateAttributesFromZuoraLookupPercentage", 0, 0.seconds, 30.seconds) {
      updatePercentage("UpdateAttributesFromZuoraLookupPercentage")
    }

  private def updatePercentage(featureName: String): Future[Int] = featureToggleService.get("AttributesFromZuoraLookup").map {
    case Some(feature) =>
      val percentage = feature.TrafficPercentage.getOrElse(0)
      logger.info(s"$featureName scheduled task set percentage to $percentage")
      updateZuoraTrafficPercentageTask.agent.alter(percentage)
      percentage
    case None =>
      logger.info(s"Tried to update the percentage of traffic for $featureName, but that feature was not " +
      "found in the table. Setting traffic to 0%")
      updateZuoraTrafficPercentageTask.agent.alter(0)
      0
  }

  updateZuoraTrafficPercentageTask.start()

  def getPercentageTrafficForZuoraLookup = updateZuoraTrafficPercentageTask.agent.get()
}