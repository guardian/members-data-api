package services

import models.subscription.subsv2.SubscriptionPlan.{AnyPlan, Free, Paid}
import models.subscription.subsv2.{PaidChargeList, Subscription}

object DifferentiateSubscription {
  def differentiateSubscription(subscription: Subscription[AnyPlan]): Either[Subscription[Free], Subscription[Paid]] = {
    subscription.plan.charges match {
      case _: PaidChargeList => Right(subscription.asInstanceOf[Subscription[Paid]])
      case _ => Left(subscription.asInstanceOf[Subscription[Free]])
    }
  }
}
