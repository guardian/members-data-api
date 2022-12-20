package monitoring

import akka.actor.ActorSystem
import com.gu.monitoring.CloudWatch
import com.typesafe.scalalogging.LazyLogging
import configuration.ApplicationName

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class Metrics(service: String, stage: String) extends CloudWatch {
  val application = ApplicationName.applicationName // This sets the namespace for Custom Metrics in AWS (see CloudWatch)
}

// Designed for high-frequency metrics, for example, 1000 per minute is about $400 per month
final class ExpensiveMetrics(
    val service: String,
    val stage: String,
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends CloudWatch
    with LazyLogging {
  import scala.jdk.CollectionConverters._
  private val chm = new ConcurrentHashMap[String, AtomicInteger]().asScala // keep it first in the constructor

  val application = ApplicationName.applicationName

  system.scheduler.scheduleAtFixedRate(5.seconds, 60.seconds)(() => publishAllMetrics())

  def countRequest(key: String): Unit =
    chm.getOrElseUpdate(key, new AtomicInteger(1)).incrementAndGet()

  private def resetCount(key: String): Unit =
    chm.getOrElseUpdate(key, new AtomicInteger(0)).set(0)

  private def publishAllMetrics(): Unit =
    chm foreach { case (key, value) =>
      put(key, value.doubleValue())
      resetCount(key)
    }
}
