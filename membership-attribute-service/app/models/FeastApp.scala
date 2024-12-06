package models

import models.FeastApp.IosSubscriptionGroupIds.IntroductoryOffer
import org.joda.time.LocalDate

object FeastApp {

  val FeastFullLaunchDate = LocalDate.parse("2024-07-10")

  object IosSubscriptionGroupIds {
    // Subscription group ids are used by the app to tell the app store which subscription option to show to the user
    val IntroductoryOffer = "21396030"
  }

  object AndroidOfferTags {
    // Offer tags are the Android equivalent of iOS subscription groups - used by the app to work out which offer to show to the user
    val IntroductoryOffer = "initial_supporter_launch_offer"
  }

  private def isBeforeFeastLaunch(dt: LocalDate): Boolean = dt.isBefore(FeastFullLaunchDate)

  def shouldGetFeastAccess(attributes: Attributes): Boolean =
    attributes.isPartnerTier ||
      attributes.isPatronTier ||
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
