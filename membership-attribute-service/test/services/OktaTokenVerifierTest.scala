package services

import com.okta.jwt.{AccessTokenVerifier, Jwt, JwtVerificationException}
import org.mockito.Mockito.when
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import java.time.Instant
import java.util
import scala.jdk.CollectionConverters._


class OktaTokenVerifierTest extends Specification with Mockito {

  //just a convenience method for creating a mock jwt that lets us leave some claims out to test error conditionsm
  def getMockJwt(omittedClaims:List[String] = List.empty) = {
    val allClaims = Map(
      "sub" -> "testEmailAddress",
      "identity_username" -> "testUserName",
      "legacy_identity_id" -> "testIdentityId",
      "scp" -> List("scope1", "scope2", "scope3").asJava
    )

    val filteredClaims = allClaims.filterNot{case (key,value) => omittedClaims.contains(key)}

    new Jwt {
      override val getTokenValue: String = "notUsingRawTokenValue"

      override val getIssuedAt: Instant = Instant.parse("2022-10-30T18:35:24.00Z")

      override val getExpiresAt: Instant = Instant.parse("2022-10-30T19:35:24.00Z")

      override val getClaims: util.Map[String, AnyRef] = filteredClaims.asJava
    }
  }

  def getMockJwt(omittedClaim: String):Jwt = getMockJwt(List(omittedClaim))


  val validToken = "testTokenValue"
  val invalidToken = "invalid_token"
  val tokenMissingSub = "token_missing_sub"
  val tokenMissingLegacyIdentityId = "token_missing_legacy_identity_id"
  val tokenMissingUserName = "token_missing_username"
  //just a convenience function to generate a mock function that returns a fixed value for the "accessToken" header when called from the verifier
  def mockFetchToken(response: String)(requestedHeader: String) = if (requestedHeader == "accessToken") Some(response) else None

  val testUserIdentifiers = UserIdentifiers(
    primaryEmailAddress = "testEmailAddress",
    identityId = "testIdentityId",
    username = Some("testUserName")
  )

  val underlyingOktaVerifier = mock[AccessTokenVerifier]
  when(underlyingOktaVerifier.decode(validToken)).thenReturn(getMockJwt())
  when(underlyingOktaVerifier.decode(tokenMissingSub)).thenReturn(getMockJwt(omittedClaim = "sub"))
  when(underlyingOktaVerifier.decode(tokenMissingUserName)).thenReturn(getMockJwt(omittedClaim = "identity_username"))
  when(underlyingOktaVerifier.decode(tokenMissingLegacyIdentityId)).thenReturn(getMockJwt(omittedClaim = "legacy_identity_id"))

  val oktaLibraryValidationException = new JwtVerificationException("okta library says token is invalid!")
  when(underlyingOktaVerifier.decode(invalidToken)).thenThrow(oktaLibraryValidationException)
  val oktaTokenVerifier = new OktaTokenVerifier(underlyingOktaVerifier)

  //TODO test cases for when the token doesn't have the required claims like scp


  "OktaTokenVerifier.userFromAccessToken" should {
    "return user claims when valid token with no required scopes" in {
      val actual = oktaTokenVerifier.userFromAccessToken(validToken)
      actual mustEqual (Right(testUserIdentifiers))
    }

    "return user claims when valid token with required scopes" in {
      val requiredScopes = List("scope1", "scope2")
      val actual = oktaTokenVerifier.userFromAccessToken(validToken, requiredScopes)
      actual mustEqual (Right(testUserIdentifiers))
    }

    "return missing scopes error when not all required scopes are in token" in {
      val requiredScopes = List("scope_not_in_token", "scope1", "another_scope_not_in_token", "scope2")
      val actual = oktaTokenVerifier.userFromAccessToken(validToken, requiredScopes)
      actual mustEqual (Left(MissingRequiredScope(List("scope_not_in_token", "another_scope_not_in_token"))))
    }

    "return error when token is missing required claims" in {
      val actual = oktaTokenVerifier.userFromAccessToken(invalidToken)
      actual mustEqual (Left(OktaValidationError(oktaLibraryValidationException)))
    }

    "return missing scopes error when token is missing 'sub' claim" in {
      val actual = oktaTokenVerifier.userFromAccessToken(tokenMissingSub)
      actual mustEqual (Left(MissingRequiredClaim("sub")))
    }
    "return missing scopes error when token is missing 'legacy_identity_id' claim" in {
      val actual = oktaTokenVerifier.userFromAccessToken(tokenMissingLegacyIdentityId)
      actual mustEqual (Left(MissingRequiredClaim("legacy_identity_id")))
    }

    "return error when not underlying decoder cannot decode token" in {
      val actual = oktaTokenVerifier.userFromAccessToken(invalidToken)
      actual mustEqual (Left(OktaValidationError(oktaLibraryValidationException)))
    }

  }
  //here we could potentially duplicate all the test cases from the previous one
  "OktaTokenVerifier.userFromHeader" should {
    "fetch header and process successfully when valid token with no required scopes" in {
      val actual = oktaTokenVerifier.userFromHeader(mockFetchToken(validToken))
      actual mustEqual (Right(testUserIdentifiers))
    }

    "etch header and process successfully when valid token with required scopes" in {
      val requiredScopes = List("scope1", "scope2")
      val actual = oktaTokenVerifier.userFromHeader(mockFetchToken(validToken), requiredScopes)
      actual mustEqual (Right(testUserIdentifiers))
    }

    "return error when not underlying decoder cannot decode fetched token" in {
      val actual = oktaTokenVerifier.userFromHeader(mockFetchToken(invalidToken))
      actual mustEqual (Left(OktaValidationError(oktaLibraryValidationException)))
    }
  }
}

