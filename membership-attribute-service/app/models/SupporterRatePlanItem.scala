package models

import org.joda.time.LocalDate

case class SupporterRatePlanItem(
    identityId: String, //Unique identifier for user
    ratePlanId: String, //Unique identifier for this product purchase for this user
    productRatePlanId: String, //Unique identifier for the product in this rate plan
    termEndDate: LocalDate //Date that this subscription term ends
)
