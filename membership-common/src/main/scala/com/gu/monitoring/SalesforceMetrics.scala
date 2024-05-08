package com.gu.monitoring

import com.amazonaws.regions.{Region, Regions}
import com.gu.monitoring.SafeLogger.LogPrefix

class SalesforceMetrics(val stage: String, val application: String)
    extends CloudWatch
    with StatusMetrics
    with RequestMetrics
    with AuthenticationMetrics {

  val region = Region.getRegion(Regions.EU_WEST_1)
  val service = "Salesforce"

  def recordRequest()(implicit logPrefix: LogPrefix) {
    putRequest
  }

  def recordResponse(status: Int, responseMethod: String)(implicit logPrefix: LogPrefix) {
    putResponseCode(status, responseMethod)
  }

  def recordAuthenticationError() {
    putAuthenticationError
  }
}
