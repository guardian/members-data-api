package models

import org.joda.time.LocalDate

case class DynamoSupporterRatePlanItem(
    subscriptionName: String, // Unique identifier for the subscription
    identityId: String, // Unique identifier for user
    productRatePlanId: String, // Unique identifier for the product in this rate plan
    termEndDate: LocalDate, // Date that this subscription term ends
    contractEffectiveDate: LocalDate, // Date that this subscription started
)
