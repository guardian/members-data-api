package filters

import com.gu.identity.testing.usernames.TestUsernames

class IsTestUser(testUsernames: TestUsernames) {
  def apply(displayName: Option[String]): Boolean =
    displayName.flatMap(_.split(' ').headOption).exists(testUsernames.isValid)

}
