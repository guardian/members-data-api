package loghandling

//import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.util.EC2MetadataUtils
import configuration.Config
import play.api.Configuration
import com.gu.aws.CredentialsProvider
import com.typesafe.scalalogging.StrictLogging
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

case class LogStashConf(enabled: Boolean,
  stream: String,
  region: String,
  awsCredentialsProvider: AwsCredentialsProvider,
  customFields: Map[String, String])

object Logstash extends StrictLogging {

  def customFields(playConfig: Config.type) = Map(
    "stack" -> "unknownStack",// all TODO
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
