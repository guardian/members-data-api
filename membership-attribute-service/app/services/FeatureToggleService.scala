package services

import models.FeatureToggle

import scala.concurrent.Future
import scalaz.\/

trait FeatureToggleService extends HealthCheckableService {
  def get(featureName: String): Future[\/[String, FeatureToggle]]
}
