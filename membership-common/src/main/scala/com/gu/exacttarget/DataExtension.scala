package com.gu.exacttarget

sealed trait DataExtension {
  val name: String

  def getJsonKeyForSNSMessage = "DataExtensionName"

  def getTSKey: String
}

trait WelcomeEmailDataExtension extends DataExtension {
  override def getTSKey: String = s"triggered-send-keys.$name.welcome1"
}

case object PaperVoucherDataExtension extends WelcomeEmailDataExtension {
  val name = "paper-voucher"
}

case object PaperDeliveryDataExtension extends WelcomeEmailDataExtension {
  val name = "paper-delivery"
}

case object PrintFailedExtension extends DataExtension {
  val name = "print-failed"
  override val getTSKey = "triggered-send-keys.print.failed"
}

case object DigipackDataExtension extends WelcomeEmailDataExtension {
  val name = "digipack"
}

case object MembershipDataExtension extends WelcomeEmailDataExtension {
  val name = "membership"
}

case object GuardianWeeklyWelcome1DataExtension extends WelcomeEmailDataExtension {
  val name = "guardian-weekly"
}

case object GuardianWeeklyRenewalDataExtension extends DataExtension {
  val name = "guardian-weekly-renewal"
  override val getTSKey = "triggered-send-keys.guardian-weekly.renewal1"
}

case object HolidaySuspensionBillingScheduleExtension extends DataExtension {
  val name = "holiday-suspension-billing-schedule"
  override val getTSKey = s"triggered-send-keys.$name.template1"
}

case object ContributionThankYouExtension extends DataExtension {
  val name = "contribution-thank-you"
  override val getTSKey = "triggered-send-keys.contribution.thank-you"
}

case object RegularContributionThankYouExtension extends DataExtension {
  val name = "regular-contribution-thank-you"
  override val getTSKey = "triggered-send-keys.contribution.regular-thank-you"
}

case object ContributionFailedExtension extends DataExtension {
  val name = "contribution-failed"
  override val getTSKey = "triggered-send-keys.contribution.failed"
}

case object DigipackFailedExtension extends DataExtension {
  val name = "digipack-failed"
  override val getTSKey = "triggered-send-keys.digipack.failed"
}

case object GuardianWeeklyFailedExtension extends DataExtension {
  val name = "guardian-weekly-failed"
  override val getTSKey = "triggered-send-keys.guardian-weekly.failed"
}

case object SupporterAbandonedCartEaseExtension extends DataExtension {
  val name = "supporter-abandoned-checkout-ease-email"
  override val getTSKey = "triggered-send-keys.supporter-abandoned-checkout.ease"
}

case object FirstFailedPaymentExtension extends DataExtension {
  val name = "first-failed-payment-email"
  override val getTSKey = "triggered-send-keys.failed-payment-email.first"
}

case object SecondFailedPaymentExtension extends DataExtension {
  val name = "second-failed-payment-email"
  override val getTSKey = "triggered-send-keys.failed-payment-email.second"
}

case object ThirdFailedPaymentExtension extends DataExtension {
  val name = "third-failed-payment-email"
  override val getTSKey = "triggered-send-keys.failed-payment-email.third"
}

object DataExtension {

  val extensionsByName = Set(
    PaperDeliveryDataExtension,
    PaperVoucherDataExtension,
    DigipackDataExtension,
    HolidaySuspensionBillingScheduleExtension,
    ContributionThankYouExtension,
    RegularContributionThankYouExtension,
    ContributionFailedExtension,
    DigipackFailedExtension,
    PrintFailedExtension,
    GuardianWeeklyFailedExtension,
    GuardianWeeklyWelcome1DataExtension,
    GuardianWeeklyRenewalDataExtension,
    SupporterAbandonedCartEaseExtension,
    FirstFailedPaymentExtension,
    SecondFailedPaymentExtension,
    ThirdFailedPaymentExtension,
  ).map(e => e.name -> e).toMap

  def getByName(name: String): Option[DataExtension] = extensionsByName.get(name)
}
