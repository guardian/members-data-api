package models

import models.subscription.subsv2.Subscription
import models.subscription.subsv2.SubscriptionPlan.AnyPlan
import services.zuora.rest.ZuoraRestService.AccountObject

case class AccountWithSubscriptions(account: AccountObject, subscriptions: List[Subscription[AnyPlan]])
