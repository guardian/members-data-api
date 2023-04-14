package com.gu.monitoring

import com.amazonaws.regions.{Region, Regions}

class ServiceMetrics(val stage: String,
                     val application: String,
                     val service: String) extends CloudWatch
                                          with StatusMetrics
                                          with RequestMetrics
                                          with AuthenticationMetrics {

  val region = Region.getRegion(Regions.EU_WEST_1)

  def recordRequest() {
    putRequest
  }

  def recordResponse(status: Int, responseMethod: String) {
    putResponseCode(status, responseMethod)
  }

  def recordAuthenticationError() {
    putAuthenticationError
  }

  def recordError() {
    put("error-count", 1)
  }
}