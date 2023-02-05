package memsub.subsv2.services

import com.typesafe.config.ConfigFactory
import configuration.ids.SubsV2ProductIds
import memsub.subsv2.Fixtures.productIds
import okhttp3._
import org.specs2.mutable.Specification
import scalaz.\/
import services.catalog.{CatalogService, FetchCatalog}
import services.zuora.ZuoraRestConfig
import services.zuora.rest.SimpleClient
import util.Await.waitFor
import util.{CreateNoopMetrics, Resource}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class CatalogServiceTest extends Specification {
  import scalaz.Scalaz.futureInstance

  "Catalog service" should {
    "Read a catalog in UAT" in {
      val cats =
        new CatalogService(productIds, FetchCatalog.fromZuoraApi(CatalogServiceTest.client("rest/CatalogUat.json")), waitFor(_, 0 nanos), "UAT")
      waitFor(cats.catalog, 5 seconds).map(_ => true) mustEqual \/.right(true)
    }

    "Read a catalog in DEV with the config product IDs" in {
      val dev = ConfigFactory.parseResources("touchpoint.DEV.conf")
      val ids = SubsV2ProductIds(dev.getConfig("touchpoint.backend.environments.DEV.zuora.productIds"))
      val cats = new CatalogService(ids, FetchCatalog.fromZuoraApi(CatalogServiceTest.client("rest/Catalog.json")), waitFor(_, 0 nanos), "DEV")
      waitFor(cats.catalog, 5 seconds).map(_ => true) mustEqual \/.right(true)
    }
  }
}

object CatalogServiceTest {
  import scalaz.Scalaz.futureInstance

  def client(path: String) = {
    val runner = (r: Request) =>
      Future.successful(
        new Response.Builder()
          .request(r)
          .message("test")
          .code(200)
          .body(ResponseBody.create(MediaType.parse("application/json"), Resource.getJson(path).toString))
          .protocol(Protocol.HTTP_1_1)
          .build(),
      )

    import io.lemonlabs.uri.dsl._
    val restConfig = ZuoraRestConfig("foo", "http://localhost", "joe", "public")
    import scala.concurrent.ExecutionContext.Implicits.global
    SimpleClient(restConfig, runner, CreateNoopMetrics)
  }

}
