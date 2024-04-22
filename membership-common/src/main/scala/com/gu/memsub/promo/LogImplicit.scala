package com.gu.memsub.promo

import com.gu.monitoring.SafeLogging

object LogImplicit {

  implicit class Loggable[T](t: T) extends SafeLogging {
    def withLogging(message: String): T = {
      logger.info(s"$message {$t}")
      t
    }

  }

}
