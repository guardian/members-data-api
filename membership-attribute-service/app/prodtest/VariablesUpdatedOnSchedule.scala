package prodtest

import akka.actor.ActorSystem
import com.gu.memsub.util.ScheduledTask
import com.typesafe.scalalogging.LazyLogging
import services.AttributeService

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class VariablesUpdatedOnSchedule(scanamoAttributeService: AttributeService)(implicit ec: ExecutionContext, system: ActorSystem) extends LazyLogging {

  private var percentageTrafficForZuoraLookup = 0

  private val updateZuoraTrafficPercentageTask =
    ScheduledTask[Int]("UpdateZuoraTrafficPercentage", 0, 0.seconds, 30.seconds) {
      scanamoAttributeService.get("trafficThroughZuoraLookup") map { attr => //todo: storing in attributes table hack
        val percentage = attr.get.Tier.get

        percentageTrafficForZuoraLookup = percentage.toInt
        logger.info(s"Checked Zuora traffic percentage. $percentageTrafficForZuoraLookup of traffic should get attributes via Zuora.")
        percentageTrafficForZuoraLookup
      }
    }
  updateZuoraTrafficPercentageTask.start()

  def getPercentageTrafficForZuoraLookup = percentageTrafficForZuoraLookup
}