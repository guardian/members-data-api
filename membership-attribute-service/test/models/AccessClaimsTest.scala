package models

import com.gu.identity.auth.{MissingRequiredClaim, UnparsedClaims}
import com.gu.identity.model.{PublicFields, StatusFields, User}
import org.specs2.mutable.Specification

class AccessClaimsTest extends Specification {

  val identityId = "someIdentityId"
  val username = "username"
  val email = "some@email.com"
  val rawClaims = Map(
    "legacy_identity_id" -> identityId,
    "identity_username" -> username,
    "sub" -> email,
    "email_validated" -> Boolean.box(true),
    "unused" -> "unusedValue"
  )

  val parsedClaims = AccessClaims(
    identityId = identityId,
    username = Some(username),
    primaryEmailAddress = email,
    userEmailValidated = Some(true)
  )

  "AccessClaimsParser.fromUnparsed" should {
    "parse claims with emailvalidated" in {
      val unparsedClaims =  UnparsedClaims(rawClaims)

      val Right(actual) = AccessClaimsParser.fromUnparsed(unparsedClaims)

      actual shouldEqual(parsedClaims)
    }
    "parse claims without optional fields" in {
      val onlyRequiredRawClaims = rawClaims.removedAll(List("identity_username", "email_validated"))
      val onlyRequiredUnparsed =  UnparsedClaims(onlyRequiredRawClaims)

      val Right(actual) = AccessClaimsParser.fromUnparsed(onlyRequiredUnparsed)

      val onlyRequiredAccessClaims = parsedClaims.copy(username = None, userEmailValidated = None)
      actual shouldEqual(onlyRequiredAccessClaims)
    }

    def assertErrorReturnedOnMissingRequiredClaim(requiredClaimName: String) = {
      val missingRequiredUnparsedClaims =  UnparsedClaims(rawClaims.removed(requiredClaimName))
      AccessClaimsParser.fromUnparsed(missingRequiredUnparsedClaims) shouldEqual Left(MissingRequiredClaim(requiredClaimName))
    }

    "return error if missing identity id " in {
      assertErrorReturnedOnMissingRequiredClaim("legacy_identity_id")
    }
    "return error if missing email " in {
      assertErrorReturnedOnMissingRequiredClaim("sub")
    }
  }
  "AccessClaimsParser.fromUser" should {
    "populate claims" in {
      val testUser = User(
        primaryEmailAddress = email,
        id = identityId,
        publicFields = PublicFields(
          username = Some(username)
        ),
        statusFields = StatusFields(
          userEmailValidated = Some(true)
        )
      )

      AccessClaimsParser.fromUser(testUser) shouldEqual parsedClaims

    }
  }

}
