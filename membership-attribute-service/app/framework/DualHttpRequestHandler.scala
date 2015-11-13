package framework

import play.api.http.{DefaultHttpRequestHandler, HttpConfiguration, HttpErrorHandler}
import play.api.mvc.{EssentialFilter, RequestHeader}
import play.api.routing.Router

class DualHttpRequestHandler(prodRouter: Router, testRouter: Router, errorHandler: HttpErrorHandler, configuration: HttpConfiguration, filters: EssentialFilter*) extends DefaultHttpRequestHandler(prodRouter, errorHandler, configuration, filters:_*) {
  override def routeRequest(request: RequestHeader) = RouterSwitcher.selectRouter(prodRouter, testRouter, request).handlerFor(request)
}