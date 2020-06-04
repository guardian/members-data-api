package services

import models.FeatureToggle

import scala.concurrent.Future

trait FeatureToggleService extends HealthCheckableService {
  def get(featureName: String): Future[Either[String, FeatureToggle]]
}
