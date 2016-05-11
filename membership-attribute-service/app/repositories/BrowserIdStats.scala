package repositories

import com.amazonaws.services.dynamodbv2.model.{ComparisonOperator, Condition, QueryRequest}
import com.github.dwhjames.awswrap.dynamodb._
import configuration.Config

import scala.collection.convert.decorateAsScala._
import scala.collection.convert.wrapAsScala._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object BrowserIdStats {
  val BrowserId = "browserId"

  val tableName: String = s"friendly-tailor-${Config.stage}"

  def getPathsByTagFor(browserId: String, tags: Set[String]): Future[Map[String, Set[String]]]= {
    val condition = new Condition()
      .withComparisonOperator(ComparisonOperator.EQ)
      .withAttributeValueList(new AttributeValue().withS(browserId))

    val q = new QueryRequest()
        .withTableName(tableName)
        .addKeyConditionsEntry(BrowserId, condition)

    for {
      queryResult <- Config.dynamoMapper.client.query(q)
    } yield (for {
        tag <- tags
      } yield tag -> (for {
        item <- queryResult.getItems.map(_.asScala)
        pathSet <- item.get(tag).toSeq
        path: String <- pathSet.getSS
      } yield path).toSet
    ).toMap
  }
}