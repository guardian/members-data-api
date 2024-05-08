package controllers

import com.gu.monitoring.SafeLogging
import components.TouchpointBackends
import play.api.libs.json.Json
import play.api.mvc.{BaseController, ControllerComponents}
import services.HealthCheckableService

trait Test {
  def ok: Boolean
  def messages: Seq[String] = Nil
}

class BoolTest(name: String, exec: () => Boolean) extends Test {
  override def messages = List(s"Test $name failed, health check will fail")
  override def ok = exec()
}

class HealthCheckController(touchPointBackends: TouchpointBackends, override val controllerComponents: ControllerComponents)
    extends BaseController
    with SafeLogging {

  val touchpointComponents = touchPointBackends.normal
  // behaviourService, Stripe and all Zuora services are not critical
  private lazy val services: Set[HealthCheckableService] = Set(
    touchpointComponents.salesforceService,
    touchpointComponents.zuoraSoapService,
  )

  private lazy val tests = services.map(service => new BoolTest(service.serviceName, () => service.checkHealth))

  def healthCheck() = Action {
    Cached(1) {
      val failures = tests.filterNot(_.ok)

      if (failures.isEmpty) {
        Ok(Json.obj("status" -> "ok", "gitCommitId" -> app.BuildInfo.gitCommitId))
      } else {
        failures.flatMap(_.messages).foreach(msg => logger.warnNoPrefix(msg))
        ServiceUnavailable("Service Unavailable")
      }
    }
  }

}
