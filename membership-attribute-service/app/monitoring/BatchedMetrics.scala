package monitoring

import org.apache.pekko.actor.ActorSystem
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync
import com.amazonaws.services.cloudwatch.model.StandardUnit
import com.typesafe.scalalogging.{LazyLogging, StrictLogging}
import configuration.ApplicationName

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

// Designed for high-frequency metrics, for example, 1000 per minute is about $400 per month
final class BatchedMetrics(
    cloudwatch: CloudWatch,
)(implicit system: ActorSystem, ec: ExecutionContext) {
  import scala.jdk.CollectionConverters._
  private val countMap = new ConcurrentHashMap[String, AtomicInteger]().asScala // keep it first in the constructor

  val application = ApplicationName.applicationName

  system.scheduler.scheduleAtFixedRate(5.seconds, 60.seconds)(() => publishAllMetrics())

  def incrementCount(key: String): Unit =
    countMap.getOrElseUpdate(key, new AtomicInteger(1)).incrementAndGet()

  private def resetCount(key: String): Unit =
    countMap.getOrElseUpdate(key, new AtomicInteger(0)).set(0)

  private def publishAllMetrics(): Unit =
    countMap.foreach { case (key, value) =>
      cloudwatch.put(key, value.doubleValue(), StandardUnit.Count)
      resetCount(key)
    }
}
