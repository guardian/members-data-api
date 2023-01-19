package configuration

import com.gu.identity.testing.usernames.{Encoder, TestUsernames}
import com.typesafe.config.Config

import java.time.Duration

object CreateTestUsernames {
  def from(config: Config) = TestUsernames(Encoder.withSecret(config.getString("identity.test.users.secret")), Duration.ofDays(2))
}
