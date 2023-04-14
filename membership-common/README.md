Membership-common
=================

The latest version of membership-common is:

![](https://maven-badges.herokuapp.com/maven-central/com.gu/membership-common_2.13/badge.svg)

Playframework library shared between:

+ [membership-frontend](https://github.com/guardian/membership-frontend)
+ ~[subscriptions-frontend](https://github.com/guardian/subscriptions-frontend)~
+ [membership-workflow](https://github.com/guardian/membership-workflow)
+ [members-data-api](https://github.com/guardian/members-data-api)
+ [memsub-promotions](https://github.com/guardian/memsub-promotions)

This library helps establish the contract between Salesforce, Stripe, Zuora, CAS and CloudWatch metrics.

Releasing to local repo
==================

Run `sbt publishLocal`.


Releasing to maven
==================

We use teamcity to release to Maven.  Follow these steps to release a new version.

1. push/merge your changes to the default branch
1. wait for the build to finish successfully.
1. use the version listed by the build to import into dependent projects
