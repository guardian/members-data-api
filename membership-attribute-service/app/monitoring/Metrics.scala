package monitoring

import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync
import configuration.ApplicationName

import scala.concurrent.{ExecutionContext, Future}

case class Metrics(service: String, stage: String, cloudwatch: AmazonCloudWatchAsync) extends CloudWatch {
  val application = ApplicationName.applicationName // This sets the namespace for Custom Metrics in AWS (see CloudWatch)

  def increaseCount(metricName: String): Unit = put(metricName + " count", 1, "count")

  def reportDuration(metricName: String, duration: Long): Unit = put(metricName + " duration ms", duration, "ms")

  def measureDuration[T](metricName: String)(block: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    logger.debug(s"$metricName started...")
    increaseCount(metricName)
    val startTime = System.currentTimeMillis()

    def recordEnd[A](metricName: String)(value: A): A = {
      val duration = System.currentTimeMillis() - startTime
      reportDuration(metricName, duration)
      logger.debug(s"${service} $metricName completed in $duration ms")

      value
    }

    block.transform(recordEnd(metricName), recordEnd(s"$metricName failed"))
  }
}
