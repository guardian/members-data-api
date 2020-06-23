package monitoring

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import com.amazonaws.services.cloudwatch.model.{MetricDatum, PutMetricDataRequest, StatisticSet}
import com.gu.monitoring.CloudWatch
import com.gu.monitoring.CloudWatch.cloudwatch
import com.typesafe.scalalogging.LazyLogging
import configuration.Config

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class Metrics(service: String) extends CloudWatch {
  val stage = Config.stage
  val application = Config.applicationName // This sets the namespace for Custom Metrics in AWS (see CloudWatch)
}

//// Designed for high-frequency metrics, for example, 1000 per minute is about $450 per month
//final class ExpensiveMetrics(
//  val service: String
//)(implicit system: ActorSystem, ec: ExecutionContext) extends CloudWatch with LazyLogging {
//  logger.info("expensive - created instance")
//
//  import scala.collection.JavaConverters._
//  private val chm = new ConcurrentHashMap[String, Int]().asScala
//
//  val stage = Config.stage
//  val application = Config.applicationName
//
//  system.scheduler.schedule(5.seconds, 60.seconds)(publishAllMetrics())
//
//  def countRequest(key: String): Unit = {
//    chm.get(key) match {
//      case Some(value) => chm.update(key, value + 1)
//      case None => chm.update(key, 1)
//    }
//  }
//
//  private def resetCount(key: String) = chm.replace(key, 0)
//
//  private def publishAllMetrics(): Unit =
//    chm foreach { case (key, value) =>
//      put(key, value)
//      resetCount(key)
//    }
//}

// Designed for high-frequency metrics, for example, 1000 per minute is about $450 per month
final class ExpensiveMetrics(
  val service: String
)(implicit system: ActorSystem, ec: ExecutionContext) extends CloudWatch with LazyLogging {
  logger.info("expensive - created instance")

  import scala.collection.JavaConverters._
  private val chm = new ConcurrentHashMap[String, AtomicInteger]().asScala

  val stage = Config.stage
  val application = Config.applicationName

  system.scheduler.schedule(5.seconds, 60.seconds)(publishAllMetrics())

  def countRequest(key: String): Unit = {
    chm.getOrElseUpdate(key, new AtomicInteger(1)).incrementAndGet()
  }
  private def resetCount(key: String): Unit =
    chm.getOrElseUpdate(key, new AtomicInteger(0)).set(0)

  private def publishAllMetrics(): Unit =
    chm foreach { case (key, value) =>
      put(key, value.doubleValue())
      resetCount(key)
    }
}
