package com.gu.memsub.promo

import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object LogImplicit {

  implicit class LoggableFuture[T](eventualT: Future[T]) extends SafeLogging {
    def withLogging(message: String)(implicit logPrefix: LogPrefix, ec: ExecutionContext): Future[T] = {
      eventualT.onComplete {
        case Failure(exception) => logger.warn(s"Failed: $message", exception)
        case Success(_) => logger.info(s"Success: $message")
      }
      eventualT
    }
  }

  implicit class Loggable[T](t: T) extends SafeLogging {
    def withLogging(message: String)(implicit logPrefix: LogPrefix): T = {
      logger.info(s"$message {$t}")
      t
    }

  }

}
