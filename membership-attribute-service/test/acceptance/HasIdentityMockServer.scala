package acceptance

import acceptance.util.AvailablePort
import org.mockserver.integration.ClientAndServer

trait HasIdentityMockServer {
  val identityPort = AvailablePort.find()
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
