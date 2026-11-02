package com.gu.monitoring

import com.gu.monitoring.SafeLogger.LogPrefix

trait ApplicationMetrics extends CloudWatch {
  val application: String
  val stage: String
}

trait StatusMetrics extends CloudWatch {
  def putResponseCode(status: Int, responseMethod: String)(implicit logPrefix: LogPrefix) {
    val statusClass = status / 100
    put(s"${statusClass}XX-response-code", 1, responseMethod)
  }
}

trait RequestMetrics extends CloudWatch {
  def putRequest()(implicit logPrefix: LogPrefix) {
    put("request-count", 1)
  }
}

trait AuthenticationMetrics extends CloudWatch {
  def putAuthenticationError {
    put("auth-error", 1)(LogPrefix.noLogPrefix)
  }
}

object CloudWatchHealth {
  var hasPushedMetricSuccessfully = false
}
