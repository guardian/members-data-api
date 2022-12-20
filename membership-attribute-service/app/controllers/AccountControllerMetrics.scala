package controllers

import com.amazonaws.regions.{Region, Regions}
import com.gu.monitoring.CloudWatch

class AccountControllerMetrics(val stage: String) extends CloudWatch {
  val region = Region.getRegion(Regions.EU_WEST_1)
  val service = "AccountController"
  val application = "members-data-api"

}
