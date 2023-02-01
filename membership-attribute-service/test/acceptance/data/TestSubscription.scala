package acceptance.data

import acceptance.data.Randoms.randomId
import models.subscription
import models.subscription.promo.PromoCode
import models.subscription.subsv2.SubscriptionPlan.AnyPlan
import models.subscription.subsv2.{CovariantNonEmptyList, ReaderType, Subscription}
import org.joda.time.{DateTime, LocalDate}

object TestSubscription {
  def apply(
      id: subscription.Subscription.Id = subscription.Subscription.Id(randomId("subscriptionId")),
      name: subscription.Subscription.Name = subscription.Subscription.Name(randomId("subscriptionName")),
      accountId: subscription.Subscription.AccountId = subscription.Subscription.AccountId(randomId("accountId")),
      startDate: LocalDate = LocalDate.now().minusDays(7),
      acceptanceDate: LocalDate = LocalDate.now().plusDays(2),
      termStartDate: LocalDate = LocalDate.now().minusDays(5),
      termEndDate: LocalDate = LocalDate.now().plusDays(12).plusYears(1),
      casActivationDate: Option[DateTime] = None,
      promoCode: Option[PromoCode] = None,
      isCancelled: Boolean = false,
      hasPendingFreePlan: Boolean = false,
      plans: CovariantNonEmptyList[AnyPlan] = CovariantNonEmptyList(TestPaidSubscriptionPlan(), Nil),
      readerType: ReaderType = ReaderType.Direct,
      gifteeIdentityId: Option[String] = None,
      autoRenew: Boolean = false,
  ): Subscription[AnyPlan] =
    Subscription[AnyPlan](
      id: subscription.Subscription.Id,
      name,
      accountId,
      startDate,
      acceptanceDate,
      termStartDate,
      termEndDate,
      casActivationDate,
      promoCode,
      isCancelled,
      hasPendingFreePlan,
      plans,
      readerType,
      gifteeIdentityId,
      autoRenew,
    )
}
