package models

import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.zuora.rest.ZuoraRestService.AccountSummary

case class AccountWithSubscription(account: AccountSummary, subscription: Option[Subscription[AnyPlan]])
