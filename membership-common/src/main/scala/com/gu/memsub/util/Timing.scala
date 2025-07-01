package com.gu.memsub.util

import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.{CloudWatch, SafeLogging}

import scala.concurrent.{ExecutionContext, Future}

object Timing extends SafeLogging {

  def record[T](cloudWatch: CloudWatch, metricName: String)(block: => Future[T])(implicit ec: ExecutionContext, logPrefix: LogPrefix): Future[T] = {
    logger.debug(s"$metricName started...")
    cloudWatch.put(metricName, 1)
    val startTime = System.currentTimeMillis()

    def recordEnd[A](name: String)(a: A): A = {
      val duration = System.currentTimeMillis() - startTime
      cloudWatch.put(name + " duration ms", duration)
      logger.debug(s"${cloudWatch.service} $name completed in $duration ms")

      a
    }

    block.transform(recordEnd(metricName), recordEnd(s"$metricName failed"))
  }
}
