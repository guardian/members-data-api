package controllers

import play.api.libs.json.Json
import play.api.mvc.{BaseController, ControllerComponents, Results}
import components.TouchpointBackends
import com.typesafe.scalalogging.StrictLogging

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
    with StrictLogging {

  val touchpointComponents = touchPointBackends.normal
  // behaviourService, Stripe and all Zuora services are not critical
  private lazy val services = Set(
    touchpointComponents.salesforceService,
    touchpointComponents.zuoraService,
  )

  private lazy val tests = services.map(service => new BoolTest(service.serviceName, () => service.checkHealth))

  def healthCheck() = Action {
    Cached(1) {
      val failures = tests.filterNot(_.ok)

      if (failures.isEmpty) {
        Ok(Json.obj("status" -> "ok", "gitCommitId" -> app.BuildInfo.gitCommitId))
      } else {
        failures.flatMap(_.messages).foreach(msg => logger.warn(msg))
        ServiceUnavailable("Service Unavailable")
      }
    }
  }

}
