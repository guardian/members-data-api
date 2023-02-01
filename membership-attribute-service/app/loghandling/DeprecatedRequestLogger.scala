package loghandling

import monitoring.SafeLogger
import play.api.mvc.{AnyContent, WrappedRequest}

object DeprecatedRequestLogger {

  val deprecatedSearchPhrase = "DeprecatedEndpointCalled"

  def logDeprecatedRequest(request: WrappedRequest[AnyContent]): Unit = {
    SafeLogger.info(s"$deprecatedSearchPhrase ${request.method} ${request.path} with headers ${request.headers.toMap} with body ${request.body}")
  }
}
