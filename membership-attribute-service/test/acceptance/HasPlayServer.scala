package acceptance

import play.api.ApplicationLoader.Context
import play.api.inject.ApplicationLifecycle
import play.api.{Application, Configuration, Environment, Mode}
import play.core.server.{AkkaHttpServer, ServerConfig}
import wiring.{AppLoader, MyComponents}

import java.io.File
import scala.concurrent.Future

trait HasPlayServer {
  this: HasIdentityMockServer =>

  val playPort = 8081
  val playServerAddress = "http://localhost:" + playPort

  var application: Application = _
  var server: AkkaHttpServer = _

  def createMyComponents(context: Context) = new MyComponents(context)

  def startPlayServer() = {
    val appLoader = new AppLoader {
      override protected def createMyComponents(context: Context): MyComponents =
        HasPlayServer.this.createMyComponents(context)
    }
    val configuration = Configuration
      .load(Environment(new File("."), Configuration.getClass.getClassLoader, Mode.Prod))
    application = appLoader
      .load(Context(
        Environment.simple(),
        Configuration(
          "http.port" -> playPort,
          "touchpoint.backend.environments.DEV.identity.apiUrl" -> identityServerUrl
        )
          .withFallback(configuration),
        lifecycle,
        None
      ))
    server = AkkaHttpServer.fromApplication(application, ServerConfig(port = Some(playPort)))
  }

  def stopPlayServer() = {
    application.stop()
    server.stop()
  }

  val lifecycle: ApplicationLifecycle = new ApplicationLifecycle {
    override def addStopHook(hook: () => Future[_]): Unit = {}

    override def stop(): Future[_] = Future.unit
  }
}
