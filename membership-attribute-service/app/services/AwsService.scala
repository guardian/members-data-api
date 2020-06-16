package services

import com.amazonaws.services.autoscaling.AmazonAutoScalingClientBuilder
import com.gu.aws.CredentialsProvider
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._
object AwsService extends App with LazyLogging {
  def getCurrentNumberOfInstances(stage: String): Int =
    AmazonAutoScalingClientBuilder
      .standard()
      .withRegion("eu-west-1")
      .withCredentials(CredentialsProvider)
      .build()
      .describeAutoScalingGroups()
      .getAutoScalingGroups
      .asScala
      .toList
      .filter(_.getAutoScalingGroupName.startsWith(s"Memb-Attributes-$stage"))
      .map(_.getInstances.asScala.size)
      .headOption
      .getOrElse(throw new RuntimeException("There should exist at least one scaling group. Fix ASAP!"))
}
