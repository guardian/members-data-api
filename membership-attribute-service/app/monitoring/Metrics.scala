package monitoring

import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync
import com.amazonaws.services.cloudwatch.model.StandardUnit
import utils.SimpleEitherT
import utils.SimpleEitherT.SimpleEitherT

import scala.concurrent.{ExecutionContext, Future}

case class Metrics(service: String, stage: String, cloudwatch: AmazonCloudWatchAsync) extends CloudWatch {
  def incrementCount(metricName: String): Unit = put(metricName + " count", 1, StandardUnit.Count)

  def measureDurationEither[T](metricName: String)(block: => SimpleEitherT[T])(implicit ec: ExecutionContext): SimpleEitherT[T] =
    SimpleEitherT(measureDuration(metricName)(block.run))

  def measureDuration[T](metricName: String)(block: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    logger.debug(s"$metricName started...")
    incrementCount(metricName)
    val startTime = System.currentTimeMillis()

    def recordEnd[A](name: String)(value: A): A = {
      val duration = System.currentTimeMillis() - startTime
      put(name + " duration ms", duration, StandardUnit.Milliseconds)
      logger.debug(s"$service $name completed in $duration ms")

      value
    }

    block.transform(recordEnd(metricName), recordEnd(s"$metricName failed"))
  }
}
