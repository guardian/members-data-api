package models

import org.joda.time.LocalDate

case class SupporterRatePlanItem(
    identityId: String, //Unique identifier for user
    gifteeIdentityId: Option[String], //Unique identifier for user if this is a DS gift subscription
    ratePlanId: String, //Unique identifier for this product purchase for this user
    productRatePlanId: String, //Unique identifier for the product in this rate plan
    productRatePlanName: String, //Name of the product in this rate plan
    termEndDate: LocalDate //Date that this subscription term ends
)
