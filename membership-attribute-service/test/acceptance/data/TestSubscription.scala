package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.memsub
import com.gu.memsub.promo.PromoCode
import com.gu.memsub.subsv2.{CovariantNonEmptyList, ReaderType, Subscription, SubscriptionPlan}
import org.joda.time.{DateTime, LocalDate}

object TestSubscription {
  def apply(
      id: memsub.Subscription.Id = memsub.Subscription.Id(randomId("subscriptionId")),
      name: memsub.Subscription.Name = memsub.Subscription.Name(randomId("subscriptionName")),
      accountId: memsub.Subscription.AccountId = memsub.Subscription.AccountId(randomId("accountId")),
      startDate: LocalDate = LocalDate.now().minusDays(7),
      acceptanceDate: LocalDate = LocalDate.now().plusDays(2),
      termStartDate: LocalDate = LocalDate.now().minusDays(5),
      termEndDate: LocalDate = LocalDate.now().plusDays(12).plusYears(1),
      casActivationDate: Option[DateTime] = None,
      promoCode: Option[PromoCode] = None,
      isCancelled: Boolean = false,
      hasPendingFreePlan: Boolean = false,
      plans: CovariantNonEmptyList[SubscriptionPlan] = CovariantNonEmptyList(TestPaidSubscriptionPlan(), Nil),
      readerType: ReaderType = ReaderType.Direct,
      gifteeIdentityId: Option[String] = None,
      autoRenew: Boolean = false,
  ): Subscription =
    Subscription(
      id: memsub.Subscription.Id,
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
