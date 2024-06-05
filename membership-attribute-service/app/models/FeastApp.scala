package models

import models.FeastApp.IosSubscriptionGroupIds.{ExtendedTrial, RegularSubscription}
import org.joda.time.LocalDate
import scalaz.Scalaz.ToBooleanOpsFromBoolean

object FeastApp {

  val FeastIosLaunchDate = LocalDate.parse("2024-04-01")

  object IosSubscriptionGroupIds {
    // Subscription group ids are used by the app to tell the app store which subscription option to show to the user
    val ExtendedTrial = "21445388"
    val RegularSubscription = "21396030"
  }

  object AndroidOfferTags {
    // Offer tags are the Android equivalent of iOS subscription groups - used by the app to work out which offer to show to the user
    val InitialSupporterLaunchOffer = "initial_supporter_launch_offer"
  }

  private def isBeforeFeastLaunch(dt: LocalDate): Boolean = dt.isBefore(FeastIosLaunchDate)

  def shouldGetFeastAccess(attributes: Attributes): Boolean =
    attributes.isPartnerTier ||
      attributes.isPatronTier ||
      attributes.isGuardianPatron ||
      attributes.digitalSubscriberHasActivePlan ||
      attributes.isSupporterPlus

  private def isRecurringContributorWhoSubscribedBeforeFeastLaunch(attributes: Attributes) =
    attributes.isRecurringContributor && attributes.RecurringContributionAcquisitionDate.exists(isBeforeFeastLaunch)

  private def shouldGetFreeTrial(attributes: Attributes) =
    isRecurringContributorWhoSubscribedBeforeFeastLaunch(attributes) ||
      attributes.isPremiumLiveAppSubscriber ||
      attributes.isGuardianWeeklySubscriber ||
      attributes.isSupporterTier ||
      attributes.isPaperSubscriber

  private def shouldShowSubscriptionOptions(attributes: Attributes) = !shouldGetFeastAccess(attributes)

  def getFeastIosSubscriptionGroup(attributes: Attributes): Option[String] =
    shouldShowSubscriptionOptions(attributes).option(
      if (shouldGetFreeTrial(attributes))
        ExtendedTrial
      else
        RegularSubscription,
    )
  def getFeastAndroidOfferTags(attributes: Attributes): Option[String] =
    if (shouldShowSubscriptionOptions(attributes) && shouldGetFreeTrial(attributes))
      Some(AndroidOfferTags.InitialSupporterLaunchOffer)
    else None
}
