package services

import models.subscription.subsv2.SubscriptionPlan.{Free, Paid}
import models.subscription.subsv2.{PaidChargeList, Subscription}
import models.ContactAndSubscription

object DifferentiateSubscription {
  def differentiateSubscription(contactAndSubscription: ContactAndSubscription): Either[Subscription[Free], Subscription[Paid]] = {
    contactAndSubscription.subscription.plan.charges match {
      case _: PaidChargeList => Right(contactAndSubscription.subscription.asInstanceOf[Subscription[Paid]])
      case _ => Left(contactAndSubscription.subscription.asInstanceOf[Subscription[Free]])
    }
  }
}
