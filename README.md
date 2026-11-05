# Members' Data API

The members' data API is a Play app that manages and retrieves supporter attributes associated with a user.  
It runs on https://members-data-api.theguardian.com/.

### Dotcom
theguardian.com website is the biggest single consumer of `members-data-api`, specifically the `/user-attributes/me` endpoint, which it uses to determine both ad-free (when user has a digital subscription or supporter plus or a newspaper product) and if we should hide 'support messaging/asks' (banner, epic, header/footer support buttons etc).

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
- DCR [dotcom-rendering/src/web/lib/contributions.ts](https://github.com/guardian/dotcom-rendering/blob/main/dotcom-rendering/src/lib/contributions.ts#L10)
- Dotcom
  - [frontend/static/src/javascripts/projects/common/modules/commercial/user-features.ts](https://github.com/guardian/frontend/blob/main/static/src/javascripts/projects/common/modules/commercial/user-features.ts#L17)
  - [frontend/blob/master/common/app/templates/inlineJS/blocking/applyRenderConditions.scala.js](https://github.com/guardian/frontend/blob/master/common/app/templates/inlineJS/blocking/applyRenderConditions.scala.js)

### User attributes data sources
User attributes (which products a user currently holds) are served from three sources
1. The `SupporterProductData-[STAGE]` DynamoDB table - This table is populated by a scheduled extract
from Stripe (Guardian Patrons only) and Zuora (digital and print subscriptions & recurring contributions)
2. The `contributions-store-[STAGE]` Postgres database (one off contributions only)
3. The [mobile purchases api](https://github.com/guardian/mobile-purchases) (in-app purchases only)

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

1. (optional) Create an ssh tunnel to the CODE one-off contributions database:
    1. Clone https://github.com/guardian/contributions-platform
    1. From the contributions-platform project, Run `./contributions-store/contributions-store-bastion/scripts/open_ssh_tunnel.sh -s CODE` (requires [marauder](https://github.com/guardian/prism/tree/master/marauder))
    1. If you need to close your tunnel and didn't make a note of the process number you can run `ps aux | grep amazonaws.com` to find the process number. The output will look something like this `username      1693   0.0  0.0 34153208    952   ??  Ss    4:20pm   0:00.00 ssh -i /private/var/folders/yv/dbtm9psd5ddbjm_lvcjm9zf1vdng_j/T/security_ssm-scala_temporary-rsa-private-key.tmp -f machine.eu-west-1.compute.amazonaws.com -L 5432:contributions-store-code.address.rds.amazonaws.com:5432 -N -o IdentitiesOnly yes -o ExitOnForwardFailure yes` where `1693` is the process number. You can close the tunnel with `kill 1693`.

1. Ensure an `nginx` service is running locally. You can run `dev-nginx restart-nginx` to do this.

### Identity frontend local sign in
The /me endpoints can either use the GU_U and SC_GU_U from the Cookie request header or Bearer authorisation header.
To get either of these you will need to sign in to the identity frontend.

To authenticate and get a bearer token from identity (recommended)
1. Follow this identity thread to set up PostMan https://chat.google.com/room/AAAAFdv9gK8/kxmrtiVjtRs/

To run identity locally for browser based access (not recommended)
1. Start up a local Identity service by running script `make dev` in the [gateway](https://github.com/guardian/gateway) repo.
1. Go to https://profile.thegulocal.com/signin.

### Starting the API
1. To start the Members' data API service run `./start-api.sh`.  
The service will be running on 9400.

1. go to https://members-data-api.thegulocal.com/user-attributes/me.
If you get a 401 response, it probably means your Identity credentials have expired.  
Renew them by following the steps in [Identity frontend local sign in](#identity-frontend-local-sign-in)

1. As of 22/04/2022 the https://members-data-api.thegulocal.com/user-attributes/me endpoint should work correctly if your set up is correct. Other endpoints (`/healthcheck`, `/user-attributes/me/mma-membership`) may not work correctly due to upstream dependencies.

## Running tests

run sbt and then test

### Identity Frontend

Identity frontend is split between [new (profile-origin)](https://github.com/guardian/identity-frontend) and old (profile), which is the identity project in [frontend](https://github.com/guardian/frontend). Only profile uses the membership-attribute-service. Make sure that it's pointing at your local instance.

    devOverrides{
             guardian.page.userAttributesApiUrl="https://members-data-api.thegulocal.com/user-attributes"
             id.members-data-api.url="https://members-data-api.thegulocal.com/"
    }
 
## API Docs

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
    
## SSH into instances

`membership-attribute-service` is installed as systemd service via `.deb` package.

Application instances allow SSH via SSM tunnel, not via port 22,
 
1. Get fresh Janus credentials
1. Find instance `prism -f instanceName attribute code`
1. 1ssm ssh -x -i i-123456 --profile membership`

| Description           | Command |
| --------------------- | ------------------------------------------------------------------------ |
| application directory | `/usr/share/membership-attribute-service`                                |
| service config        | `/lib/systemd/system/membership-attribute-service.service`               |
| application logs      | `/var/log/membership-attribute-service/membership-attribute-service.log` |
| stdout/stderr logs    | `journalctl -u membership-attribute-service`                             |
| service status        | `systemctl status membership-attribute-service`                          |
| healthcheck           | `curl http://127.0.0.1:9000/healthcheck`                                 |

## Enabling fine metrics

To enable fine metrics for all http calls to external services (Zuora Rest and SOAP, Salesforce, etc), set this in config (either application.conf or s3://gu-reader-revenue-private/membership/members-data-api/DEV/members-data-api.private.conf):

`use-fine-metrics=true`

When set, calls to all methods / endpoints will be logged as metrics.

You can see it on these dashboards:

CODE: https://eu-west-1.console.aws.amazon.com/cloudwatch/home?region=eu-west-1#dashboards:name=Members-Data-API-CODE

PROD: https://eu-west-1.console.aws.amazon.com/cloudwatch/home?region=eu-west-1#dashboards:name=Members-Data-API 
