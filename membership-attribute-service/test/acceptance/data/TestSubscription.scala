package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.memsub
import com.gu.memsub.promo.PromoCode
import com.gu.memsub.subsv2.{ReaderType, Subscription, RatePlan}
import org.joda.time.{DateTime, LocalDate}

object TestSubscription {
  def apply(
      id: memsub.Subscription.Id = memsub.Subscription.Id(randomId("subscriptionId")),
      name: memsub.Subscription.Name = memsub.Subscription.Name(randomId("subscriptionName")),
      accountId: memsub.Subscription.AccountId = memsub.Subscription.AccountId(randomId("accountId")),
      startDate: LocalDate = LocalDate.now().minusDays(7),
      acceptanceDate: LocalDate = LocalDate.now().plusDays(2),
      termEndDate: LocalDate = LocalDate.now().plusDays(12).plusYears(1),
      isCancelled: Boolean = false,
      plans: List[RatePlan] = List(TestPaidSubscriptionPlan()),
      readerType: ReaderType = ReaderType.Direct,
      autoRenew: Boolean = false,
  ): Subscription =
    Subscription(
      id: memsub.Subscription.Id,
      name,
      accountId,
      startDate,
      acceptanceDate,
      termEndDate,
      isCancelled,
      plans,
      readerType,
      autoRenew,
    )
}
