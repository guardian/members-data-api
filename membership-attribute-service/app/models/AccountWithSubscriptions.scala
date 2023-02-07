package models

import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import services.zuora.rest.ZuoraRestService.AccountObject

case class AccountWithSubscriptions(account: AccountObject, subscriptions: List[Subscription[AnyPlan]])
