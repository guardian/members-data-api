package acceptance

import org.mockserver.integration.ClientAndServer

trait HasIdentityMockServer {
  val identityPort = 1080
  val identityServerUrl = s"http://localhost:$identityPort"
  val identityApiToken = "db5e969d58bf6ad42f904f56191f88a0"
  protected var identityMockClientAndServer: ClientAndServer = _

  def startIdentityMockServer(): Unit = {
    identityMockClientAndServer = new ClientAndServer(identityPort)
  }

  def stopIdentityMockServer(): Unit = {
    if (identityMockClientAndServer != null) {
      identityMockClientAndServer.stop()
    }
  }
}
