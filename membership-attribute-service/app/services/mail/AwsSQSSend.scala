package services.mail

import play.api.libs.json.{JsObject, JsString, JsValue, Json}
import software.amazon.awssdk.auth.credentials.{
  AwsCredentialsProviderChain,
  EnvironmentVariableCredentialsProvider,
  ProfileCredentialsProvider,
  SystemPropertyCredentialsProvider,
}

case class QueueName(value: String) extends AnyVal

object AwsSQSSend {

  val ProfileName = "membership"

  lazy val CredentialsProvider: AwsCredentialsProviderChain = AwsCredentialsProviderChain.builder
    .credentialsProviders(
      EnvironmentVariableCredentialsProvider.create(),
      SystemPropertyCredentialsProvider.create(),
      ProfileCredentialsProvider.builder.profileName(ProfileName).build(),
    )
    .build()
}

case class EmailData(emailAddress: String, salesforceContactId: String, campaignName: String, dataPoints: Map[String, String]) {
  def toJson: JsValue = {
    Json.parse(
      s"""
        |{
        |   "To":{
        |      "Address": "$emailAddress",
        |      "ContactAttributes":{
        |         "SubscriberAttributes": ${mapToJson(dataPoints)}
        |      }
        |   },
        |   "DataExtensionName": "$campaignName",
        |   "SfContactId": "$salesforceContactId"
        |}""".stripMargin,
    )
  }

  private def mapToJson(map: Map[String, String]): JsObject = {
    JsObject(map.map { case (key, value) =>
      key -> JsString(value)
    }.toSeq)
  }
}
