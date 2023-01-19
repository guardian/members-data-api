package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.memsub.Subscription.Feature

object TestFeature {
  def apply(id: Feature.Id = Feature.Id(randomId("featureId")), code: Feature.Code = Feature.Code.Events): Feature = Feature(id, code)
}
