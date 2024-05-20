package models

import com.gu.memsub.subsv2.Subscription
import com.gu.salesforce.Contact

case class ContactAndSubscription(
    contact: Contact,
    subscription: Subscription,
    isGiftRedemption: Boolean,
)
