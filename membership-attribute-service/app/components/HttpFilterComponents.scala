package components
import configuration.Config._
import filters.{LogRequestsFilter, AddEC2InstanceHeader}
import play.api.BuiltInComponents
import play.api.libs.ws.ning.NingWSComponents
import play.filters.cors.CORSFilter
import play.filters.csrf.CSRFComponents

trait HttpFilterComponents { self: BuiltInComponents with CSRFComponents with NingWSComponents =>
  override lazy val httpFilters = Seq(new AddEC2InstanceHeader(wsApi), LogRequestsFilter, CORSFilter(corsConfig))
}
