package services

import org.mockito.IdiomaticMockito
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import services.mail.EmailData

class EmailDataTest extends Specification with IdiomaticMockito {

  "EmailData" should {

    "generate correct json" in {
      EmailData(
        "my.email@email.com",
        "mySalesforceId",
        "myCampaignName",
        Map(
          "data_point_1" -> "value 1",
          "data_point_2" -> "value 2",
          "data_point_3" -> "value 3",
        ),
      ).toJson shouldEqual Json.parse(
        """
            |{
            |   "To":{
            |      "Address":"my.email@email.com",
            |      "ContactAttributes":{
            |         "SubscriberAttributes":{
            |            "data_point_1":"value 1",
            |            "data_point_2":"value 2",
            |            "data_point_3":"value 3"
            |         }
            |      }
            |   },
            |   "DataExtensionName":"myCampaignName",
            |   "SfContactId":"mySalesforceId"
            |}
            |""".stripMargin,
      )
    }
  }
}
