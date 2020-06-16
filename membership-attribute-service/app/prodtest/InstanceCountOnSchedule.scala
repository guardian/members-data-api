package prodtest

import akka.actor.ActorSystem
import com.gu.memsub.util.ScheduledTask
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import com.amazonaws.services.autoscaling.AmazonAutoScalingClientBuilder
import com.gu.aws.CredentialsProvider
import com.typesafe.scalalogging.LazyLogging
import scala.collection.JavaConverters._

class InstanceCountOnSchedule(stage: String)(implicit ec: ExecutionContext, system: ActorSystem) extends LazyLogging {
  private val defaultInstanceCount = 6

  private lazy val autoScalingClient =
    AmazonAutoScalingClientBuilder
      .standard()
      .withRegion("eu-west-1")
      .withCredentials(CredentialsProvider)
      .build()

  private def getCurrentNumberOfInstances(stage: String): Int =
    autoScalingClient
      .describeAutoScalingGroups()
      .getAutoScalingGroups
      .asScala
      .toList
      .filter(_.getAutoScalingGroupName.startsWith(s"Memb-Attributes-$stage"))
      .map(_.getInstances.asScala.size)
      .headOption
      .getOrElse(throw new RuntimeException("There should exist at least one auto scaling group. Fix ASAP!"))

  private val _getInstanceCountTask =
    ScheduledTask[Int]("AutoScalingGroupInstanceCount", initValue = defaultInstanceCount, 0.seconds, 30.seconds) {
      Future(getCurrentNumberOfInstances(stage)).recover { case e =>
          logger.error(s"Failed to determine AWS instance count. Failing to default value $defaultInstanceCount", e)
          defaultInstanceCount
        }
    }

  _getInstanceCountTask.start()

  def getInstanceCounTask: ScheduledTask[Int] = _getInstanceCountTask
}