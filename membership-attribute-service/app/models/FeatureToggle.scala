package models

case class FeatureToggle(FeatureName: String, TrafficPercentage: Option[Int], ConcurrentZuoraCallThreshold: Option[Int])

/**
 * @param ConcurrentZuoraCallThreshold Total Zuora concurrent requests across all EC2 instances, for example,
 *                                     if set to 12, and there are 6 instances, then it would result in limit of 2 per instance
 */
case class ZuoraLookupFeatureData(TrafficPercentage: Int, ConcurrentZuoraCallThreshold: Int)
