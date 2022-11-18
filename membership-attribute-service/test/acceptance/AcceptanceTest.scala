package acceptance

import kong.unirest.{HttpResponse, Unirest}
import org.specs2.mock.Mockito
import org.specs2.mutable.{BeforeAfter, Specification}
import play.api.{Application, Configuration, Environment, Mode, inject}
import play.api.ApplicationLoader.Context
import play.api.inject.ApplicationLifecycle
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSClient
import play.api.test.PlaySpecification
import wiring.AppLoader

import java.io.File
import java.util.concurrent.{Callable, CompletionStage}
import scala.concurrent.Future

class AcceptanceTest extends Specification with Mockito with PlaySpecification {

  "Server" should {

    val port = 8080

    val configuration = Configuration
      .load(Environment(new File("."), Configuration.getClass.getClassLoader, Mode.Prod))
    val application = (new AppLoader).load(Context(
      Environment.simple(),
      configuration.withFallback(Configuration("http.port" -> port)),
      lifecycle,
      None
    ))

    val serverAddress = s"https://localhost:$port"
    val userAttributesUrl = s"http://$serverAddress/user-attributes/me"

    "work" in {
      Thread.sleep(10000)

      val response: HttpResponse[String] = Unirest.get(userAttributesUrl).asString()
      val json = Json.parse(response.getBody)


      response.getStatus shouldEqual 200
      1 shouldEqual 1
    }
  }

  val lifecycle: ApplicationLifecycle = new ApplicationLifecycle {
    override def addStopHook(hook: () => Future[_]): Unit = {}

    override def stop(): Future[_] = Future.unit
  }
}
