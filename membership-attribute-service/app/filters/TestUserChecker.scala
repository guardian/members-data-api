package filters

import com.gu.identity.testing.usernames.TestUsernames
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging

class TestUserChecker(testUsernames: TestUsernames) extends SafeLogging {
  def isTestUser(primaryEmailAddress: String)(implicit logPrefix: LogPrefix): Boolean = {
    val maybeValidTestUser = for {
      localPart <- primaryEmailAddress.split('@').headOption
      possibleTestUsername <- localPart.split('+').toList match {
        case _ :: subAddress :: _ => Some(subAddress)
        case noPlus :: Nil => Some(noPlus)
        case _ => None // invalid email address - no @ sign
      }
    } yield testUsernames.isValid(possibleTestUsername)
    if (maybeValidTestUser.contains(true)) {
      logger.info(primaryEmailAddress + " is a test user")
    }
    maybeValidTestUser.getOrElse(false)
  }

}
