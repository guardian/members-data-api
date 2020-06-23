package monitoring

import java.util.concurrent.ConcurrentHashMap

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

// Designed for high-frequency metrics, for example, 1000 per minute is about $450 per month
final class ExpensiveMetrics(
  val service: String
)(implicit system: ActorSystem, ec: ExecutionContext) extends CloudWatch with LazyLogging {
  logger.info("expensive - created instance")

  val stage = Config.stage
  val application = Config.applicationName

  system.scheduler.schedule(0.seconds, 55.seconds)(publishAllMetrics())

  import scala.collection.JavaConverters._
  private val chm = new ConcurrentHashMap[String, Int]().asScala

  def countRequest(key: String): Unit = {
    chm.get(key) match {
      case Some(value) => chm.update(key, value + 1)
      case None => chm.update(key, 1)
    }
  }

  private def resetCount(key: String) = chm.replace(key, 0)

  private def publishAllMetrics(): Unit =
    chm foreach { case (key, value) =>
      putStatisticsSetCount(key, value)
      val result = resetCount(key)
      logger.info(s"expensive reset result $result")
    }

//  private def putStatisticsSetCount(name: String, count: Int): Unit = {
//    logger.info(s"expensive metric: $name=$count")
//    val stats = new StatisticSet()
//      .withMaximum(1d)
//      .withMinimum(1d)
//      .withSampleCount(count.toDouble)
//      .withSum(count.toDouble)
//
//    val metric = new MetricDatum()
//      .withMetricName(name)
//      .withUnit("Count")
//      .withDimensions(mandatoryDimensions:_*)
//      .withStatisticValues(stats)
//
//    val request = new PutMetricDataRequest().
//      withNamespace(application)
//      .withMetricData(metric)
//
//    cloudwatch.putMetricDataAsync(request, LoggingAsyncHandler)
//  }

  private def putStatisticsSetCount(name: String, count: Int) = {
    put(name, count)
  }
}
