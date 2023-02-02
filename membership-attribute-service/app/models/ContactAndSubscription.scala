package models

import models.subscription.subsv2.Subscription
import models.subscription.subsv2.SubscriptionPlan.AnyPlan
import services.salesforce.model.Contact

case class ContactAndSubscription(
    contact: Contact,
    subscription: Subscription[AnyPlan],
    isGiftRedemption: Boolean,
)
