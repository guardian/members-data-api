package models

import models.subscription.subsv2.Subscription
import models.subscription.subsv2.SubscriptionPlan.AnyPlan
import com.gu.salesforce.Contact

case class ContactAndSubscription(
    contact: Contact,
    subscription: Subscription[AnyPlan],
    isGiftRedemption: Boolean,
)
