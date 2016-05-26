package repositories

import java.time.Instant
import java.time.temporal.ChronoUnit.DAYS

import cats.data.Xor
import com.gu.scanamo._
import com.gu.scanamo.syntax._
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.ops._
import configuration.Config

import scala.collection.convert.decorateAsScala._
import scala.collection.convert.wrapAsScala._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object BrowserIdStats {

  implicit val instantMillisFormat = DynamoFormat.coercedXmap[Instant, Long, IllegalArgumentException](Instant.ofEpochMilli)(_.toEpochMilli)

  case class StoredPageViews(browserId: String, userId: String, pageViewsByTag: Map[String, Map[String, Instant]])

  val BrowserId = "browserId"

  val table = Table[StoredPageViews](s"friendly-tailor-${Config.stage}")

  val userIdIndex = table.index("userIdIndexKey-browserId-index")

  val client = Config.dynamoMapper.client.client

  def getStatsForBrowserId(browserId: String) = getStoredPageViewsByTag(table.query('browserId -> browserId)) _

  def getStatsForUserId(userId: String) = getStoredPageViewsByTag(userIdIndex.query('userIdIndexKey -> userId)) _

  def getStoredPageViewsByTag(pageViewsQuery: ScanamoOps[Stream[Xor[DynamoReadError,StoredPageViews]]])(tags: Set[String]): Future[Map[String, Set[String]]]= {
    val recencyThreshold = Instant.now().minus(7, DAYS)

    for {
      result: Traversable[Xor[DynamoReadError, StoredPageViews]] <- ScanamoAsync.exec(client)(pageViewsQuery)
    } yield {
      val storedPageViewsList: List[StoredPageViews] = result.toList.flatMap(_.toOption)
      (for {
        tag <- tags
      } yield tag -> (for {
        item <- storedPageViewsList
        timesByPath <- item.pageViewsByTag.get(tag).toSeq
        (path, time) <- timesByPath if time.isAfter(recencyThreshold)
      } yield path).toSet
        ).toMap
    }
  }
}