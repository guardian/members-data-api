package acceptance

import org.mockito.IdiomaticMockito
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterEach
import play.api.test.PlaySpecification

trait AcceptanceTest
    extends Specification
    with IdiomaticMockito
    with PlaySpecification
    with HasIdentityMockServer
    with HasPlayServer
    with BeforeAfterEach {
  protected def before: Unit = {
    startPlayServer()
    startIdentityMockServer()
  }

  protected def after: Unit = {
    stopIdentityMockServer()
    stopPlayServer()
  }
}
