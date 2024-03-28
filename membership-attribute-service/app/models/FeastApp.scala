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

  private def isBeforeFeastLaunch(dt: LocalDate): Boolean = dt.isBefore(FeastIosLaunchDate)

  def shouldGetFeastAccess(attributes: Attributes) =
    attributes.isStaffTier ||
      attributes.isSupporterTier ||
      attributes.isPartnerTier ||
      attributes.isPatronTier ||
      attributes.isGuardianPatron ||
      attributes.digitalSubscriberHasActivePlan ||
      attributes.isPaperSubscriber ||
      attributes.isSupporterPlus

  private def isRecurringContributorWhoSubscribedBeforeFeastLaunch(attributes: Attributes) =
    attributes.isRecurringContributor && attributes.RecurringContributionAcquisitionDate.exists(isBeforeFeastLaunch)

  private def shouldGetFreeTrial(attributes: Attributes) =
    isRecurringContributorWhoSubscribedBeforeFeastLaunch(attributes) ||
      attributes.isPremiumLiveAppSubscriber ||
      attributes.isGuardianWeeklySubscriber

  private def shouldShowSubscriptionOptions(attributes: Attributes) = !shouldGetFeastAccess(attributes)

  def getFeastIosSubscriptionGroup(attributes: Attributes) =
    shouldShowSubscriptionOptions(attributes).option(
      if (shouldGetFreeTrial(attributes))
        ExtendedTrial
      else
        RegularSubscription,
    )
}
