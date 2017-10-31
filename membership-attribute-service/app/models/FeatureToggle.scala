package models

case class FeatureToggle(FeatureName: String, TrafficPercentage: Option[Int], ConcurrentZuoraCallThreshold: Option[Int])

case class ZuoraLookupFeatureData(TrafficPercentage: Int, ConcurrentZuoraCallThreshold: Int)
