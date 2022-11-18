package acceptance

import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterEach
import play.api.test.PlaySpecification

trait AcceptanceTest extends Specification with Mockito with PlaySpecification
  with HasIdentityMockServer with HasPlayServer with BeforeAfterEach {
  protected def before: Unit = {
    startPlayServer()
    startIdentityMockServer()
  }

  protected def after: Unit = {
    stopIdentityMockServer()
    stopPlayServer()
  }
}
