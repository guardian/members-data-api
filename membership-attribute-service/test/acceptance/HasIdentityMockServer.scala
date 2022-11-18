package acceptance

import org.mockserver.integration.ClientAndServer

trait HasIdentityMockServer {
  val identityPort = 1080
  val identityServerUrl = s"http://localhost:$identityPort"
  protected var identityMockClientAndServer: ClientAndServer = _

  def startIdentityMockServer(): Unit = identityMockClientAndServer = new ClientAndServer(identityPort)

  def stopIdentityMockServer(): Unit = identityMockClientAndServer.stop()
}
