package integration

import org.apache.pekko.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import components.TouchpointComponents
import configuration.Stage
import controllers.AccountHelpers
import monitoring.CreateNoopMetrics
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

class AccountDetailsFromZuoraIntegrationTest extends Specification {

  // This is an integration test to run code locally, we don't want it running in CI
  args(skipAll = true)

  "AccountDetailsFromZuora" should {
    "fetch a list of subs" in {
      val result = Await
        .result(
          createTouchpointComponents.accountDetailsFromZuora
            .fetch("200152344", AccountHelpers.NoFilter)
            .run
            .map { list =>
              println(s"Fetched this list: $list")
              list
            },
          Duration.Inf,
        )
      result.isRight must_== true
    }
  }

  def createTouchpointComponents = {
    implicit val system = ActorSystem.create()
    lazy val conf = ConfigFactory.load()
    new TouchpointComponents(
      Stage("CODE"),
      CreateNoopMetrics,
      conf,
    )
  }
}
