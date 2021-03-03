package services

import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import services.SupporterProductDataServiceTest.dynamoClientBuilder

class SupporterProductDataServiceTest(implicit ee: ExecutionEnv) extends Specification {
  "blah" should {
    "work" in {
      val service = new SupporterProductDataService(dynamoClientBuilder.build(), "SupporterProductData-DEV", new SupporterRatePlanToAttributesMapper("DEV"))
      service.getAttributes("100003000")
        .map{
          result => System.out.println(result)
          success
        }
    }
  }

}

object SupporterProductDataServiceTest {
  lazy val dynamoClientBuilder: AmazonDynamoDBAsyncClientBuilder =
    AmazonDynamoDBAsyncClientBuilder.standard().withCredentials(com.gu.aws.CredentialsProvider).withRegion(Regions.EU_WEST_1)
}
