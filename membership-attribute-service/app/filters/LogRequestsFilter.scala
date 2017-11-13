package filters

import play.api.Logger
import play.api.mvc._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import Headers._

/** Logs all requests, the time the app took to respond, and the status of the response */
object LogRequestsFilter extends Filter {

  private val logger = Logger(this.getClass)

  def apply(f: (RequestHeader) => Future[Result])(rh: RequestHeader): Future[Result] = {
    val start = System.currentTimeMillis

    def logTime(result: Result) {
      val time = System.currentTimeMillis - start
      logger.info(s"REQUEST ${rh.realRemoteAddr} ${rh.method} ${rh.uri} took $time ms and returned ${result.header.status}")
    }

    val result = f(rh)

    result.foreach(logTime)

    result
  }
}
