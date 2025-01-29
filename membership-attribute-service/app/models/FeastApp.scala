package models

import models.FeastApp.IosSubscriptionGroupIds.IntroductoryOffer
import org.joda.time.LocalDate

object FeastApp {

  object IosSubscriptionGroupIds {
    // Subscription group ids are used by the app to tell the app store which subscription option to show to the user
    val IntroductoryOffer = "21396030"
  }

  object AndroidOfferTags {
    // Offer tags are the Android equivalent of iOS subscription groups - used by the app to work out which offer to show to the user
    val IntroductoryOffer = "initial_supporter_launch_offer"
  }

  def shouldGetFeastAccess(attributes: Attributes): Boolean =
    attributes.isPartnerTier ||
      attributes.isPatronTier ||
      attributes.isSupporterTier ||
      attributes.isGuardianPatron ||
      attributes.digitalSubscriberHasActivePlan ||
      attributes.isSupporterPlus ||
      attributes.isPaperSubscriber

  private def shouldShowSubscriptionOptions(attributes: Attributes) = !shouldGetFeastAccess(attributes)

  def getFeastIosSubscriptionGroup(attributes: Attributes): Option[String] =
    if (shouldShowSubscriptionOptions(attributes))
      Some(IntroductoryOffer)
    else None

  def getFeastAndroidOfferTags(attributes: Attributes): Option[List[String]] =
    if (shouldShowSubscriptionOptions(attributes))
      Some(List(AndroidOfferTags.IntroductoryOffer))
    else None
}
