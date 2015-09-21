import java.security.cert.CertificateFactory

package object fakes {
  val salesforceCert = {
    val resource = getClass.getResourceAsStream("/salesforce-test.cert")
    CertificateFactory.getInstance("X.509").generateCertificate(resource)
  }

}
