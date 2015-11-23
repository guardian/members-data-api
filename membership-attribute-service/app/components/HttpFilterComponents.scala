package components
import filters.{LogRequestsFilter, AddEC2InstanceHeader}
import framework.AllComponentTraits
import play.api.http.HttpFilters
import play.api.mvc.EssentialFilter
import play.filters.cors.CORSFilter

trait HttpFilterComponents { self: AllComponentTraits =>
  lazy val filters = new HttpFilters {
    override def filters: Seq[EssentialFilter] =
      Seq(
        new AddEC2InstanceHeader(wsApi),
        LogRequestsFilter,
        CORSFilter(corsConfig)
      )
  }
}
