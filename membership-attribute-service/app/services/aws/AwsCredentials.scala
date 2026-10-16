package services.aws

import software.amazon.awssdk.auth.credentials.{AwsCredentialsProviderChain, InstanceProfileCredentialsProvider, ProfileCredentialsProvider}

object AwsCredentials {
  val ProfileName = "membership"

  lazy val CredentialsProvider: AwsCredentialsProviderChain = AwsCredentialsProviderChain.builder
    .credentialsProviders(
      ProfileCredentialsProvider.create(ProfileName),
      InstanceProfileCredentialsProvider.create(),
    )
    .build()
}
