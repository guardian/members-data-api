import javax.inject.Inject

import akka.stream.Materializer
import play.api.http.DefaultHttpFilters
import filters.{AddEC2InstanceHeader, AddGuIdentityHeaders, CheckCacheHeadersFilter}
import play.filters.csrf.CSRFFilter

class Filters @Inject()(
  checkCacheHeadersFilter: CheckCacheHeadersFilter,
  csrfFilter: CSRFFilter,
  addEC2InstanceHeader: AddEC2InstanceHeader,
  addGuIdentityHeaders: AddGuIdentityHeaders)
  (implicit val mat: Materializer) extends DefaultHttpFilters(
  checkCacheHeadersFilter,
  csrfFilter,
  addEC2InstanceHeader,
  addGuIdentityHeaders
)