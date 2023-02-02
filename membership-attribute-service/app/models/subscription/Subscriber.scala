package models.subscription

import models.subscription.subsv2.{SubscriptionPlan => Plan}
import _root_.services.salesforce.model.Contact

case class Subscriber[+T <: subsv2.Subscription[Plan.AnyPlan]](subscription: T, contact: Contact)

object Subscriber {
  type PaidMember = Subscriber[subsv2.Subscription[Plan.PaidMember]]
  type FreeMember = Subscriber[subsv2.Subscription[Plan.FreeMember]]
  type Member = Subscriber[subsv2.Subscription[Plan.Member]]
}
