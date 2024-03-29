package acceptance.data

object IdentityResponse {
  def apply(
      userId: Long,
      status: String = "ok",
      email: String = "john.smith@guardian.co.uk",
      firstName: String = "John",
      lastName: String = "Smith",
  ): String =
    s"""{
       |   "status":"$status",
       |   "user":{
       |      "primaryEmailAddress":"$email",
       |      "id":"$userId",
       |      "publicFields":{
       |         "displayName":"user"
       |      },
       |      "privateFields":{
       |         "puzzleUuid":"2e1579841179722ecebb113d7417b7665da7beda0583a196dde89967aeeeb9b7",
       |         "googleTagId":"4a7e4b119ee6ffa4626936c09620bce1e257e846f3ea170a3c4476b68ff533d0",
       |         "legacyPackages":"RCO,CRE",
       |         "legacyProducts":"RCO,CRE",
       |         "firstName":"$firstName",
       |         "secondName":"$lastName"
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
