package models

import org.joda.time.LocalDate
import play.api.libs.json.{Json, Reads}

case class DynamoSupporterRatePlanItem(
  subscriptionName: String, //Unique identifier for the subscription
  identityId: String, //Unique identifier for user
  productRatePlanId: String, //Unique identifier for the product in this rate plan
  termEndDate: LocalDate, //Date that this subscription term ends
  contractEffectiveDate: LocalDate, //Date that this subscription started
  acquisitionMetadata: Option[String] // This is a json string which is saved with the acquisition by support-frontend
){
  def acquisitionMetadataAsObject = acquisitionMetadata.flatMap(jsonString =>
    Json.parse(jsonString).validate[AcquisitionMetadata].asEither.toOption
  )
}

case class AcquisitionMetadata(shouldGetDigitalSubBenefits: Boolean)

object AcquisitionMetadata {
  implicit val reads: Reads[AcquisitionMetadata] = Json.reads[AcquisitionMetadata]
}
