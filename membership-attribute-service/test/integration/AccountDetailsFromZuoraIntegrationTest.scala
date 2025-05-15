package integration

import org.apache.pekko.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import components.TouchpointComponents
import configuration.Stage
import controllers.AccountHelpers
import monitoring.CreateNoopMetrics
import org.specs2.mutable.Specification
import play.api.libs.json.Json

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import testdata.TestLogPrefix.testLogPrefix

class AccountDetailsFromZuoraIntegrationTest extends Specification {

  // This is an integration test to run code locally, we don't want it running in CI
  args(skipAll = true)

  "AccountDetailsFromZuora" should {
    "fetch a list of subs" in {
      val touchpointComponents = createTouchpointComponents
      val eventualResult = for {
        catalog <- touchpointComponents.futureCatalog
        result <- touchpointComponents.accountDetailsFromZuora
          .fetch("200421949", AccountHelpers.NoFilter)
          .run
          .map { list =>
            println(s"Fetched this list: $list")
            list
          }
      } yield result.map(
        _.foreach(accountDetails =>
          println(
            s"JSON output for ${accountDetails.subscription.subscriptionNumber.getNumber} is:\n" + Json.prettyPrint(accountDetails.toJson(catalog)),
          ),
        ),
      )
      val result = Await.result(eventualResult, Duration.Inf)
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
