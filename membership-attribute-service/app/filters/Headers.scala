package filters

import play.api.mvc

object Headers {
  implicit class EnrichedHeaders(headers: mvc.Headers) {
    def forwardedFor = headers.get("X-Forwarded-For") map { _.split(",\\s+").toList }
  }

  implicit class EnrichedRequestHeader(header: mvc.RequestHeader) {
    /** Remote address, taking X-Forwarded-For into consideration */
    def realRemoteAddr = header.headers.forwardedFor.flatMap(_.headOption).getOrElse(header.remoteAddress)
  }
}