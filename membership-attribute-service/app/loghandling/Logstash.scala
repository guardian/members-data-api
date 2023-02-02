package loghandling

import com.amazonaws.util.EC2MetadataUtils
import aws.ProfileName
import com.typesafe.scalalogging.StrictLogging
import configuration.LogstashConfig
import software.amazon.awssdk.auth.credentials.{
  AwsCredentialsProvider,
  AwsCredentialsProviderChain,
  InstanceProfileCredentialsProvider,
  ProfileCredentialsProvider,
}

case class LogStashConf(
    enabled: Boolean,
    stream: String,
    region: String,
    awsCredentialsProvider: AwsCredentialsProvider,
    customFields: Map[String, String],
)

object Logstash extends StrictLogging {

  private val CredentialsProvider = AwsCredentialsProviderChain.builder
    .addCredentialsProvider(ProfileCredentialsProvider.builder.profileName(ProfileName).build)
    .addCredentialsProvider(InstanceProfileCredentialsProvider.builder.build)
    .build()

  def customFields(config: LogstashConfig) = Map(
    "stack" -> "unknownStack", // all TODO
    "app" -> configuration.ApplicationName.applicationName,
    "stage" -> config.stage,
    "build" -> "unknownBuild",
    "revision" -> "unknownRevision",
    "ec2_instance" -> Option(EC2MetadataUtils.getInstanceId).getOrElse("Not running on ec2"),
  )

  def config(config: LogstashConfig) = for {
    stream <- config.stream
    region <- config.streamRegion
  } yield {
    LogStashConf(
      config.enabled,
      stream,
      region,
      CredentialsProvider,
      customFields(config),
    )
  }

  def init(logstashConfig: LogstashConfig): Unit = {
    config(logstashConfig).fold(logger.info("Logstash config is missing"))(LogbackConfig.init)
  }

}
