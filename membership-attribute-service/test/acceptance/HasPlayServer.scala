package acceptance

import acceptance.util.AvailablePort
import com.typesafe.config.ConfigFactory
import play.api.ApplicationLoader.Context
import play.api.inject.ApplicationLifecycle
import play.api.{Application, Configuration, Environment, Mode}
import play.core.server.{PekkoHttpServer, ServerConfig}
import wiring.{AppLoader, MyComponents}

import java.io.File
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

trait HasPlayServer {
  this: HasIdentityMockServer =>

  private val playPort = AvailablePort.find()
  private val playServerAddress = "http://localhost:" + playPort
  protected def endpointUrl(path: String) = playServerAddress + path

  var application: Application = _
  var server: PekkoHttpServer = _

  def createMyComponents(context: Context) = new MyComponents(context)

  def startPlayServer(): Unit = {
    val appLoader = new AppLoader {
      override protected def createMyComponents(context: Context): MyComponents =
        HasPlayServer.this.createMyComponents(context)
    }
    val configuration = Configuration
      .load(Environment(new File("."), Configuration.getClass.getClassLoader, Mode.Prod))
    val devPublicConf = Configuration(ConfigFactory.load("DEV.public.conf"))
    val effectiveConfiguration = Configuration(
      "http.port" -> playPort,
      "play.filters.csrf.header.bypassHeaders.X-Requested-With" -> "*",
      "play.filters.csrf.header.bypassHeaders.Csrf-Token" -> "nocheck",
      "touchpoint.backend.environments.CODE.identity.apiUrl" -> identityServerUrl,
      "touchpoint.backend.environments.CODE.identity.apiToken" -> identityApiToken,
    )
      .withFallback(devPublicConf)
      .withFallback(configuration)

    application = appLoader
      .load(
        Context(
          Environment.simple(),
          effectiveConfiguration,
          lifecycle,
          None,
        ),
      )
    server = PekkoHttpServer.fromApplication(application, ServerConfig(port = Some(playPort)))
  }

  def stopPlayServer() = {
    if (application != null) {
      Await.result(application.stop(), Duration.Inf)
    }
    if (server != null) {
      server.stop()
    }
  }

  val lifecycle: ApplicationLifecycle = new ApplicationLifecycle {
    override def addStopHook(hook: () => Future[_]): Unit = {}

    override def stop(): Future[_] = Future.unit
  }
}
