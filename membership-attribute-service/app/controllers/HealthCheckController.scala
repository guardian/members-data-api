package controllers

import play.api.libs.json.Json
import play.api.mvc.{Action, Results}
import components.NormalTouchpointComponents._
import com.github.nscala_time.time.Imports._
import com.typesafe.scalalogging.LazyLogging
import services.HealthCheckableService

trait Test {
  def ok: Boolean
  def messages: Seq[String] = Nil
}

class BoolTest(name: String, exec: () => Boolean) extends Test {
  override def messages = List(s"Test $name failed, health check will fail")
  override def ok = exec()
}

class HealthCheckController extends Results with LazyLogging {

  private lazy val wrappedZuoraService = new HealthCheckableService {
    private val duration = 2.minutes
    override def serviceName = "ZuoraService"
    override def checkHealth: Boolean =
      if (zuoraService.lastPingTimeWithin(duration)) {
        true
      } else {
        logger.error(s"${this.serviceName} has not responded within the last ${duration.getMinutes} minutes.")
        // Return true rather than false, because we don't want Zuora to fail the healthcheck.
        // However we do want to log outages because we have an SLA from Zuora where we can claim compensation.
        true
      }
  }

  private lazy val services = Set(wrappedZuoraService, salesforceService, featureToggleService, attrService)

  private lazy val tests = services.map(service => new BoolTest(service.serviceName, () => service.checkHealth))

  def healthCheck() = Action {
    Cached(1) {
      val failures = tests.filterNot(_.ok)

      if (failures.isEmpty) {
        Ok(Json.obj("status" -> "ok", "gitCommitId" -> app.BuildInfo.gitCommitId))
      } else {
        failures.flatMap(_.messages).foreach(msg => logger.error(msg))
        ServiceUnavailable("Service Unavailable")
      }
    }
  }

}
