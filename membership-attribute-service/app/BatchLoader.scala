import java.io.File

import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.model.{PutRequest, WriteRequest}
import com.github.dwhjames.awswrap.dynamodb.SingleThreadedBatchWriter
import components.NormalTouchpointComponents
import configuration.Config._
import models.Attributes
import org.slf4j.LoggerFactory
import repositories.MembershipAttributesSerializer
import sources.SalesforceCSVExport

import scala.collection.JavaConverters._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

object BatchLoader {
  private val table = NormalTouchpointComponents.dynamoAttributesTable
  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val serializer = MembershipAttributesSerializer(table)

  def writeRequest(attrs: Attributes) =
    new WriteRequest().withPutRequest(
      new PutRequest().withItem(serializer.toAttributeMap(attrs).asJava))

  // This only works on a DB that contains only fresher records
  // than the ones in the CSV
  def main(args: Array[String]) = {
    args.headOption.fold({
      logger.error("A path is expected as argument")
      sys.exit(1)
    })({ path =>
      val file = new File(path)
      dynamoMapper.scan[Attributes](Map.empty).onSuccess { case existing =>
        val existingIds = existing.map(_.UserId).toSet
        val requests = SalesforceCSVExport
          .membersAttributes(file)
          .filterNot { attrs => existingIds.contains(attrs.UserId) }
          .map(writeRequest)
        val loader = new SingleThreadedBatchWriter(table, com.gu.aws.CredentialsProvider)
        loader.client.withRegion(Regions.EU_WEST_1)
        loader.run(requests)
      }
    })
  }
}
