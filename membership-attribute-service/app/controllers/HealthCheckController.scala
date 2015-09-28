package controllers

import play.api.Logger
import play.api.mvc.{Action, Results}

case class Test(name: String, result: () => Boolean)

class HealthCheckController extends Results {

  // TODO add a meaningful test
  val tests: Seq[Test] = Nil

  def healthCheck() = Action {
    Cached(1) {
      val serviceOk = tests.forall { test =>
        val result = test.result()
        if (!result) Logger.warn(s"${test.name} test failed, health check will fail")
        result
      }

      if (serviceOk) Ok("OK") else ServiceUnavailable("Service Unavailable")
    }
  }

}
