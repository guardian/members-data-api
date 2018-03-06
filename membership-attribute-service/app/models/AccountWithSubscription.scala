package models

import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.zuora.rest.ZuoraRestService.AccountObject

case class AccountWithSubscription(account: AccountObject, subscription: Option[Subscription[AnyPlan]])
