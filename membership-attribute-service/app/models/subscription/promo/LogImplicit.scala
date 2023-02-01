package models.subscription.promo

import monitoring.SafeLogger

object LogImplicit {

  implicit class Loggable[T](t: T) {
    def withLogging(message: String): T = {
      SafeLogger.info(s"$message {$t}")
      t
    }

  }

}
