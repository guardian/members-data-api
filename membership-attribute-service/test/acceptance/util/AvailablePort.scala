package acceptance.util
import java.net.ServerSocket
import scala.util.Try

object AvailablePort {
  def find(): Int = {
    var socket: ServerSocket = null
    try {
      socket = new ServerSocket(0)
      socket.setReuseAddress(true)
      val port = socket.getLocalPort
      Try(socket.close())
      return port
    } catch {
      case e: Throwable =>
    } finally {
      Try(socket.close())
    }
    throw new IllegalStateException("Could not find a free port")
  }
}
