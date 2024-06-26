package filters

import com.gu.identity.testing.usernames.TestUsernames
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging

class TestUserChecker(testUsernames: TestUsernames) extends SafeLogging {
  def isTestUser(primaryEmailAddress: String)(implicit logPrefix: LogPrefix): Boolean = {
    val isTestUser = testUsernames.isValidEmail(primaryEmailAddress)
    if (isTestUser) {
      logger.info(primaryEmailAddress + " is a test user")
    }
    isTestUser
  }

}
