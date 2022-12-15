package acceptance

import kong.unirest.Unirest
import models.{Attributes, ContributionData}
import org.mockito.Mockito.when
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import play.api.ApplicationLoader.Context
import play.api.libs.json.Json
import services.{ContributionsStoreDatabaseService, SupporterProductDataService}
import wiring.MyComponents

import java.util.GregorianCalendar
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AttributeControllerAcceptanceTest extends AcceptanceTest {
  var databaseServiceMock: ContributionsStoreDatabaseService = _
  var supporterProductDataService: SupporterProductDataService = _

  override protected def before: Unit = {
    databaseServiceMock = mock[ContributionsStoreDatabaseService]
    supporterProductDataService = mock[SupporterProductDataService]
    super.before
  }

  override def createMyComponents(context: Context): MyComponents = {
    new MyComponents(context) {
      override lazy val dbService = databaseServiceMock
      override lazy val supporterProductDataServiceOverride = Some(supporterProductDataService)
    }
  }

  "AttributeController" should {
    val userAttributesUrl = playServerAddress + "/user-attributes/me"

    "serve current user's attributes" in {
      val identityRequest = request()
        .withMethod("GET")
        .withPath("/user/me")
        .withHeader("X-GU-ID-Client-Access-Token", "Bearer db5e969d58bf6ad42f904f56191f88a0")
        .withHeader(
          "X-GU-ID-FOWARDED-SC-GU-U",
          "WyIyMDAwNjczODgiLDE2NzY0NTcxMTk4OTcsImI3NjE1ODMyYmE5OTQ0NzM4NTA5NTU2OTZiMjM1Yjg5IiwiIiwwXQ.MC0CFFJXLff5geHhf2EY_j_BQizPkUcnAhUAmoipMhDFsFmXuHY-a_ZXVJYPUHI",
        )

      identityMockClientAndServer
        .when(
          identityRequest,
        )
        .respond(
          response()
            .withBody(identityResponse),
        )

      when(databaseServiceMock.getLatestContribution("200067388")) thenReturn Future(
        Right(
          Some(
            ContributionData(
              created = new GregorianCalendar(2999, 1, 1).getTime,
              currency = "GBP",
              amount = 11.0,
              status = "statusValue",
            ),
          ),
        ),
      )

      when(supporterProductDataService.getNonCancelledAttributes("200067388")) thenReturn Future(
        Right(
          Some(
            Attributes("200067388"),
          ),
        ),
      )

      val httpResponse = Unirest
        .get(userAttributesUrl)
        .header(
          "Cookie",
          "consentUUID=cc457984-4282-49ca-9831-0649017aa0c9_13; _ga=GA1.2.1716429135.1668613133; GU_U=WyIyMDAwNjczODgiLCIiLCJ1c2VyIiwiIiwxNjc2NDU3MTE5ODk3LDEsMTY2ODYxMzI0ODAwMCx0cnVlXQ.MCwCFHUQjxr9nm5gk15jmWID6lYYVE5NAhR11ko5vRIjNwqGprB8gCzIEPz4Rg; SC_GU_LA=WyJMQSIsIjIwMDA2NzM4OCIsMTY2ODY4MTExOTg5N10.MCwCFG4nWLgEx96EJjNxwtqKbQBNBaXDAhRYtlpkPp8s_Ysl70rkySriLUKZaw; SC_GU_U=WyIyMDAwNjczODgiLDE2NzY0NTcxMTk4OTcsImI3NjE1ODMyYmE5OTQ0NzM4NTA5NTU2OTZiMjM1Yjg5IiwiIiwwXQ.MC0CFFJXLff5geHhf2EY_j_BQizPkUcnAhUAmoipMhDFsFmXuHY-a_ZXVJYPUHI",
        )
        .asString

      httpResponse.getStatus shouldEqual 200

      val body = httpResponse.getBody
      println(body)

      val json = Json.parse(body)

      identityMockClientAndServer.verify(identityRequest)

      (json \ "userId").as[String] shouldEqual "200067388"
      (json \ "digitalSubscriptionExpiryDate").as[String] shouldEqual "2999-01-01"
      (json \ "contentAccess" \ "member").as[Boolean] shouldEqual false
      (json \ "contentAccess" \ "paidMember").as[Boolean] shouldEqual false
      (json \ "contentAccess" \ "recurringContributor").as[Boolean] shouldEqual false
      (json \ "contentAccess" \ "supporterPlus").as[Boolean] shouldEqual false
      (json \ "contentAccess" \ "digitalPack").as[Boolean] shouldEqual true
      (json \ "contentAccess" \ "guardianWeeklySubscriber").as[Boolean] shouldEqual false
      (json \ "contentAccess" \ "guardianPatron").as[Boolean] shouldEqual false
    }
  }

  val identityResponse =
    """{
      |   "status":"ok",
      |   "user":{
      |      "primaryEmailAddress":"pawel.krupinski.casual+bla@guardian.co.uk",
      |      "id":"200067388",
      |      "publicFields":{
      |         "displayName":"user"
      |      },
      |      "privateFields":{
      |         "puzzleUuid":"2e1579841179722ecebb113d7417b7665da7beda0583a196dde89967aeeeb9b7",
      |         "googleTagId":"4a7e4b119ee6ffa4626936c09620bce1e257e846f3ea170a3c4476b68ff533d0",
      |         "legacyPackages":"RCO,CRE",
      |         "legacyProducts":"RCO,CRE"
      |      },
      |      "statusFields":{
      |         "userEmailValidated":true
      |      },
      |      "dates":{
      |         "accountCreatedDate":"2022-11-16T15:40:48Z"
      |      },
      |      "userGroups":[
      |         {
      |            "path":"/sys/policies/basic-community",
      |            "packageCode":"RCO"
      |         },
      |         {
      |            "path":"/sys/policies/basic-identity",
      |            "packageCode":"CRE"
      |         }
      |      ],
      |      "adData":{
      |
      |      },
      |      "consents":[
      |         {
      |            "actor":"user",
      |            "id":"your_support_onboarding",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"personalised_advertising",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"sms",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"digital_subscriber_preview",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"offers",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"supporter_newsletter",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"events",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"similar_guardian_products",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"holidays",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"market_research_optout",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"post_optout",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"profiling_optout",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"phone_optout",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"supporter",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"jobs",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"guardian_weekly_newsletter",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         },
      |         {
      |            "actor":"user",
      |            "id":"subscriber_preview",
      |            "version":0,
      |            "consented":false,
      |            "timestamp":"2022-11-16T15:40:48Z",
      |            "privacyPolicyVersion":1
      |         }
      |      ],
      |      "hasPassword":true
      |   }
      |}""".stripMargin
}
