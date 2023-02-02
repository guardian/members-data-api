package monitoring

import com.amazonaws.services.cloudwatch.model.StandardUnit
import com.typesafe.scalalogging.StrictLogging
import utils.SimpleEitherT
import utils.SimpleEitherT.SimpleEitherT

import scala.concurrent.{ExecutionContext, Future}

case class Metrics(service: String, protected val cloudWatch: CloudWatch) extends StrictLogging {
  def incrementCount(metricName: String): Unit = cloudWatch.put(metricName + " count", 1, StandardUnit.Count)

  def measureDurationEither[T](metricName: String)(block: => SimpleEitherT[T])(implicit ec: ExecutionContext): SimpleEitherT[T] =
    SimpleEitherT(measureDuration(metricName)(block.run))

  def measureDuration[T](metricName: String)(block: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    logger.debug(s"$metricName started...")
    incrementCount(metricName)
    val startTime = System.currentTimeMillis()

    def recordEnd[A](name: String)(value: A): A = {
      val duration = System.currentTimeMillis() - startTime
      cloudWatch.put(name + " duration ms", duration, StandardUnit.Milliseconds)
      logger.debug(s"$service $name completed in $duration ms")

      value
    }

    block.transform(recordEnd(metricName), recordEnd(s"$metricName failed"))
  }
}

trait RequestMetrics {
  protected val cloudWatch: CloudWatch
  def putRequest {
    cloudWatch.put("request-count", 1, StandardUnit.Count)
  }
}

trait AuthenticationMetrics {
  protected val cloudWatch: CloudWatch
  def putAuthenticationError {
    cloudWatch.put("auth-error", 1, StandardUnit.Count)
  }
}
