package controllers

import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{Action, Results}
import components.NormalTouchpointComponents
import com.github.nscala_time.time.Imports._

trait Test {
  def ok: Boolean
  def messages: Seq[String] = Nil
}

class BoolTest(name: String, exec: () => Boolean) extends Test {
  override def messages = List(s"Test $name failed, health check will fail")
  override def ok = exec()
}

class HealthCheckController extends Results {
  lazy val zuora = NormalTouchpointComponents.zuoraService

  val tests: Seq[Test] = Seq(
    new BoolTest("ZuoraPing", () => zuora.lastPingTimeWithin(2.minutes))
  )

  def healthCheck() = Action {
    Cached(1) {
      val failures = tests.filterNot(_.ok)

      if (failures.isEmpty) {
        Ok(Json.obj("status" -> "ok", "gitCommitId" -> app.BuildInfo.gitCommitId))
      } else {
        failures.flatMap(_.messages).foreach(msg => Logger.warn(msg))
        ServiceUnavailable("Service Unavailable")
      }
    }
  }

}
