package com.gu

import software.amazon.awssdk.auth.credentials.{AwsCredentialsProviderChain, InstanceProfileCredentialsProvider, ProfileCredentialsProvider}

package object aws {
  val ProfileName = "membership"

  lazy val CredentialsProvider = AwsCredentialsProviderChain
    .builder()
    .credentialsProviders(
      ProfileCredentialsProvider.builder().profileName(ProfileName).build(),
      InstanceProfileCredentialsProvider.create(),
    )
    .build()

}
