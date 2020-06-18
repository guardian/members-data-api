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
      .flatMap(_.getInstances.asScala)
      .filter(_.getHealthStatus == "Healthy")
      .filter(_.getLifecycleState == "InService")
      .size

  val getInstanceCountTask: ScheduledTask[Int] =
    ScheduledTask[Int]("AutoScalingGroupInstanceCount", initValue = defaultInstanceCount, 0.seconds, 30.seconds) {
      Future(getCurrentNumberOfInstances(stage))
        .map { count =>
          if (count < 1) {
            logger.error(s"There should be at least one healthy in-service instance. Failing to default value $defaultInstanceCount")
            defaultInstanceCount
          } else count
        }
        .recover { case e =>
          logger.error(s"Failed to determine AWS instance count. Failing to default value $defaultInstanceCount", e)
          defaultInstanceCount
        }
    }

  getInstanceCountTask.start()

}