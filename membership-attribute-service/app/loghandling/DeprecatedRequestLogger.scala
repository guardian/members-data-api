package loghandling

import com.typesafe.scalalogging.StrictLogging
import play.api.mvc.{AnyContent, WrappedRequest}

object DeprecatedRequestLogger extends StrictLogging {

  val deprecatedSearchPhrase = "DeprecatedEndpointCalled"

  def logDeprecatedRequest(request: WrappedRequest[AnyContent]): Unit = {
    logger.info(s"$deprecatedSearchPhrase ${request.method} ${request.path} with headers ${request.headers.toMap} with body ${request.body}")
  }
}
