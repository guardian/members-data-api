package loghandling

import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import play.api.mvc.{AnyContent, WrappedRequest}

object DeprecatedRequestLogger extends SafeLogging {

  val deprecatedSearchPhrase = "DeprecatedEndpointCalled"

  def logDeprecatedRequest(request: WrappedRequest[AnyContent])(implicit logPrefix: LogPrefix): Unit = {
    logger.info(s"$deprecatedSearchPhrase ${request.method} ${request.path} with headers ${request.headers.toMap} with body ${request.body}")
  }
}
