package loghandling

import com.amazonaws.util.EC2MetadataUtils
import configuration.Config
import com.gu.aws.ProfileName
import com.typesafe.scalalogging.StrictLogging
import software.amazon.awssdk.auth.credentials.{
  AwsCredentialsProvider,
  AwsCredentialsProviderChain,
  InstanceProfileCredentialsProvider,
  ProfileCredentialsProvider
}

case class LogStashConf(
    enabled: Boolean,
    stream: String,
    region: String,
    awsCredentialsProvider: AwsCredentialsProvider,
    customFields: Map[String, String]
)

object Logstash extends StrictLogging {

  private val CredentialsProvider = AwsCredentialsProviderChain.builder
    .addCredentialsProvider(ProfileCredentialsProvider.builder.profileName(ProfileName).build)
    .addCredentialsProvider(InstanceProfileCredentialsProvider.builder.build)
    .build()

  def customFields(playConfig: Config.type) = Map(
    "stack" -> "unknownStack", // all TODO
    "app" -> playConfig.applicationName,
    "stage" -> playConfig.stage,
    "build" -> "unknownBuild",
    "revision" -> "unknownRevision",
    "ec2_instance" -> Option(EC2MetadataUtils.getInstanceId).getOrElse("Not running on ec2")
  )

  def config(playConfig: Config.type) = for {
    stream <- playConfig.Logstash.stream
    region <- playConfig.Logstash.streamRegion
  } yield {
    LogStashConf(
      playConfig.Logstash.enabled,
      stream,
      region,
      CredentialsProvider,
      customFields(playConfig)
    )
  }

  def init(playConfig: Config.type): Unit = {
    config(playConfig).fold(logger.info("Logstash config is missing"))(LogbackConfig.init)
  }

}
