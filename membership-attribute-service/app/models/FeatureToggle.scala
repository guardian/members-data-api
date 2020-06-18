package models

/**
 * @param ConcurrentZuoraCallThreshold Total Zuora concurrent requests across all EC2 instances, for example,
 *                                     if set to 12, and there are 6 instances, then it would result in limit of 2 per instance
 */
case class FeatureToggle(
  FeatureName: String,
  /* Not used anymore */ TrafficPercentage: Option[Int] = None,
  ConcurrentZuoraCallThreshold: Option[Int]
)
