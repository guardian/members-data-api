# Members' Data API

The members' data API is a Play app that manages and retrieves supporter attributes associated with a user.  
It runs on https://members-data-api.theguardian.com/.


## How do we handle Zuora 40 concurrent requests limit?

When users visit dotcom a check is made for entitlements. For example, should ads be displayed. This results in high
load on members-data-api which we need to manage. In particular Zuora does not provide a caching mechanism whilst
at the same time having 40 concurrent requests limit. Note this limit applies globally across our systems, and Zuora
does not provide segregation by a particular client. Hence, the onus is on us to manage the limit.

There are few ways we try to manage the load 
1. dotcom cookies that expire after 24 hours
1. mem-data-api DynamoDB table with particular TTL
1. mechanism to control number of concurrent Zuora requests each mem-data-api instance can make (currently there are 6)

With current load management we hit Zuora around 1000 per minute.

### Dotcom
theguardian.com website is the biggest single consumer of `members-data-api`, specifically the `/user-attributes/me` endpoint, which it uses to determine both ad-free (becuase user has digital subscription) and if we should hide 'support messaging/asks' (banner, epic, header/footer support buttons etc).

It would be unnecessary to hit `members-data-api` on every single page view, so instead it uses cookies to regulate how often calls are made. **The `gu_user_features_expiry` contains a timestamp for the 'earliest' point it would be allowed to call `members-data-api` again, and is updated whenever it does call `members-data-api` to _'now + 24hours'_.**

Various things from the `/user-attributes/me` response are stored in cookies, to be used on each render...
- `GU_AF1` the 'ad-free' cookie which is set to a timestamp for _'now + 48hours'_ if the `contentAccess.digitalPack` = `true`
- `gu_hide_support_messaging` is set to `true` if `showSupportMessaging` = `false` in the response
- `gu_action_required_for` is set to the value of `alertAvailableFor` in the response, and is used to control the display of the 'payment failure' banner
- `gu_paying_member` = `contentAccess.paidMember` in the response
- `gu_digital_subscriber` = `contentAccess.digitalPack` in the response
- `gu_recurring_contributor` = `contentAccess.recurringContributor` in the response
- `gu_one_off_contribution_date` = `oneOffContributionDate` in the response

##### Useful Links 
- DCR [dotcom-rendering/blob/master/src/web/lib/contributions.tsx](https://github.com/guardian/dotcom-rendering/blob/master/src/web/lib/contributions.tsx)
- Dotcom
  - [frontend/blob/master/static/src/javascripts/projects/common/modules/commercial/user-features.js](https://github.com/guardian/frontend/blob/master/static/src/javascripts/projects/common/modules/commercial/user-features.js)
  - [frontend/blob/master/common/app/templates/inlineJS/blocking/applyRenderConditions.scala.js](https://github.com/guardian/frontend/blob/master/common/app/templates/inlineJS/blocking/applyRenderConditions.scala.js)

### DynamoDB table

1. Count Zuora concurrent requests (per instance)
1. Get the concurrency limit set in `AttributesFromZuoraLookup` dynamodb table
1. If the count is greater than limit, then hit cache
1. If the count is less than limit and Zuora is healthy, then hit Zuora
1. If the count is less than limit and Zuora is unhealthy, then hit cache

### Limiting concurrent requests

There is a simple if-else logic applied per instance

```
if (current concurrent requests < limit from AttributesFromZuoraLookup )
  hit zuora
else
  hit cache
```

Effect of different values for `ConcurrentZuoraCallThreshold`
- 1 results in about 50/50 split between Zuora and cache
- 2 results in about 80/20
- 7 results in likely limit hits because (6 instances) x (7 concurrent reqests) = 42

**WARNING: Remember to reduce `ConcurrentZuoraCallThreshold` if instances need to scale, say in expectation of 
drastic increase of load due to breaking news.** 

## Setting it up locally

1. You will need to have [dev-nginx](https://github.com/guardian/dev-nginx) installed.

1. Follow the [nginx steps for identity-platform](https://github.com/guardian/identity-platform/blob/master/nginx/README.md#setup-nginx-with-ssl-for-dev).

1. Follow the [identity-frontend configuration steps](https://github.com/guardian/identity-frontend#configuration).

1. Then run `./setup.sh` in `nginx/`.

1. Add the following entries to your hosts file:  
```
127.0.0.1   profile.thegulocal.com
127.0.0.1   members-data-api.thegulocal.com
```

1. Get Janus credentials for membership.

1. Download the config  
(you may need to `brew install awscli` to get the command.)  
`aws s3 cp s3://gu-reader-revenue-private/membership/members-data-api/DEV/members-data-api.private.conf /etc/gu/ --profile membership`

## Running Locally

1. Get Janus credentials for membership.

1. Create an ssh tunnel to the CODE one-off contributions database:
    1. Clone https://github.com/guardian/contributions-platform
    2. From the contributions-platform project, Run `./contributions-store/contributions-store-bastion/scripts/open_ssh_tunnel.sh -s CODE` (requires [marauder](https://github.com/guardian/prism/tree/master/marauder))

1. Ensure an `nginx` service is running locally.

1. To start the Members' data API service run `./start-api.sh`.  
The service will be running on 9400 and use the SupporterAttributesFallback-DEV DynamoDB table.

1. go to https://members-data-api.thegulocal.com/user-attributes/me/mma-membership.  
If you get a 401 response, it probably means your Identity credentials have expired.  
Renew them by:
    1. Start up a local Identity service by running script `start-frontend.sh` in the `identity-frontend` repo.
    1. Go to https://profile.thegulocal.com/signin.

## Running tests

run sbt and then test.  It will download a dynamodb table from S3 and use that.  Tip: watch out for firewalls blocking the download, you may need to turn them off to stop it scanning the file.

## Testing manually

A good strategy for testing your stuff is to run a local identity-frontend, membership-frontend and members-data-api.  Then sign up for membership and hit the above url, which should return the right JSON structure.

The /me endpoints use the GU_U and SC_GU_U from the Cookie request header.

### Identity Frontend

Identity frontend is split between [new (profile-origin)](https://github.com/guardian/identity-frontend) and old (profile), which is the identity project in [frontend](https://github.com/guardian/frontend). Only profile uses the membership-attribute-service. Make sure that it's pointing at your local instance.

    devOverrides{
             guardian.page.userAttributesApiUrl="https://members-data-api.thegulocal.com/user-attributes"
             id.members-data-api.url="https://members-data-api.thegulocal.com/"
    }
 
## API Docs

The SupporterAttributesFallback Dynamo table has identity id as a primary key. Corresponding to each identity id in the table 
we have information about that user's membership, subscriptions, and/or digital pack. 

On each lookup call (i.e. /user-attributes/{me}), we derive this information from subscriptions via Zuora, 
and then update the entry if it's out of date. If we can't get subscriptions from Zuora, we fall back to the 
SupporterAttributesFallback table. There is a TTL on the SupporterAttributesFallback table. 

### GET /user-attributes/me

#### User is a member

    {
        "userId": "xxxx",
        "tier": "Supporter",
        "membershipNumber": "1234",
        "membershipJoinDate": "2017-06-26",
        "contentAccess": {
            "member": true,
            "paidMember": true,
            "recurringContributor": false,
            "digitalPack": false

        }
    }

#### User is a contributor and not a member 
    
    {
        "userId":"xxxx",
        "recurringContributionPaymentPlan":"Monthly Contribution",
        "contentAccess": {
            "member":false,
            "paidMember":false,
            "recurringContributor":true,
            "digitalPack": false

        }
    }


#### User is not a member and not a contributor
    
    {
        "message":"Not found",
        "details":"Could not find user in the database",
        "statusCode":404
    }


#### User is a member and a contributor

    {
        "userId": "xxxx",
        "tier": "Supporter",
        "membershipNumber": "324154",
        "recurringContributionPaymentPlan": "Monthly Contribution",
        "membershipJoinDate": "2017-06-26",
        "contentAccess": {
            "member": true,
            "paidMember": true,
            "recurringContributor": true,
            "digitalPack": false

        }
    }
    
#### User has a digital pack only

    {
        "userId": "30000549",
        "digitalSubscriptionExpiryDate": "2018-11-29",
        "contentAccess": {
            "member": false,
            "paidMember": false,
            "recurringContributor": false,
            "digitalPack": true
        }
    }


### GET /user-attributes/me/membership


#### User is a member

    {
        "userId": "xxxx",
        "tier": "Supporter",
        "membershipNumber": "1234",
        "contentAccess": {
            "member": true,
            "paidMember": true
         }
    }

#### User is a contributor and not a member 

    {
        "message":"Not found",
        "details":"User was found but they are not a member",
        "statusCode":404
    }


#### User is a member and contributor

    {
        "userId": "xxxx",
        "tier": "Supporter",
        "membershipNumber": "1234",
        "contentAccess": {
            "member": true,
            "paidMember": true
        }
    }


#### User is not a member and not a contributor

    {
        "message":"Not found",
        "details":"Could not find user in the database",
        "statusCode":404
    }


### GET /user-attributes/me/features
Responses:

    {
      "adFree": true,
      "adblockMessage": false,
      "userId": "123",
      "membershipJoinDate": "2017-04-04"
    }
