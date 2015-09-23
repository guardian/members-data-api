import java.io.File

import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.model.{PutRequest, WriteRequest}
import com.github.dwhjames.awswrap.dynamodb.{AttributeValue, SingleThreadedBatchWriter}
import configuration.Config._
import models.MembershipAttributes
import org.slf4j.LoggerFactory
import sources.SalesforceCSVExport

import scala.collection.JavaConverters._

object BatchLoader {
  val logger = LoggerFactory.getLogger(getClass)

  def writeRequest(attrs: MembershipAttributes) = {
    new WriteRequest()
      .withPutRequest(
        new PutRequest()
          .withItem(
            Map(
              "UserId" -> attrs.userId,
              "Tier" -> attrs.tier,
              "MembershipNumber" -> attrs.membershipNumber
            ).mapValues(new AttributeValue(_)).asJava
          )
      )
  }

  def main(args: Array[String]) = {
    args.headOption.fold({
      logger.error("A path is expected as argument")
      sys.exit(1)
    })({ path =>
      val file = new File(path)
      val requests = SalesforceCSVExport.membersAttributes(file).map(writeRequest)
      val loader = new SingleThreadedBatchWriter(dynamoTable, AWS.credentials)
      loader.client.withRegion(Regions.EU_WEST_1)
      loader.run(requests)
    })
  }
}
