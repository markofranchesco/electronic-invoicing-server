package pe.invoicing.app.config.sunat.credentials;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.config.sunat.models.SigningMaterial;
import pe.invoicing.app.service.authentication.AutenticacionService;
import pe.invoicing.app.service.authentication.port.SecretVault;
import pe.invoicing.app.service.company.models.CertificateBundle;
import pe.invoicing.app.service.company.port.CompanyStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.Enumeration;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

@Component
@RequiredArgsConstructor
public class SunatEmissionContext {
  private final AutenticacionService authOperations;
  private final CompanyStore companyStore;
  private final SecretVault secretVault;
  private final Clock clock;

  public SigningMaterial signingMaterial(String documentNumber) {
    try {
      String encrypted =
              companyStore
                      .findEncryptedCertificate(authOperations.accountId(), documentNumber)
                      .orElseThrow(
                              () ->
                                      new ResponseStatusException(
                                              SERVICE_UNAVAILABLE, "Falta certificado configurado."));
      CertificateBundle stored = CertificateBundle.parse(secretVault.decrypt(encrypted));
      if (stored.password().isBlank())
        throw new ResponseStatusException(
                UNPROCESSABLE_CONTENT, "El certificado guardado está incompleto.");
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(
              new ByteArrayInputStream(Base64.getDecoder().decode(stored.content())),
              stored.password().toCharArray());
      Enumeration<String> aliases = ks.aliases();
      while (aliases.hasMoreElements()) {
        String alias = aliases.nextElement();
        if (ks.isKeyEntry(alias)
                && ks.getKey(alias, stored.password().toCharArray()) instanceof PrivateKey key) {
          X509Certificate certificate = (X509Certificate) ks.getCertificate(alias);
          certificate.checkValidity(Date.from(clock.instant()));
          if (!"RSA".equalsIgnoreCase(key.getAlgorithm()))
            throw new ResponseStatusException(
                    UNPROCESSABLE_CONTENT, "Se requiere certificado con clave RSA para XMLDSig.");
          if (!certificate.getSubjectX500Principal().getName().contains(documentNumber))
            throw new ResponseStatusException(
                    UNPROCESSABLE_CONTENT,
                    "El certificado no identifica el TAX_NUMBER de esta empresa; esta versión no"
                            + " utiliza certificados PSE.");
          return new SigningMaterial(key, certificate);
        }
      }
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT,
              "El certificado no contiene una clave privada para firmar los comprobantes.");
    } catch (IOException | GeneralSecurityException | IllegalArgumentException ex) {
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT,
              "No se pudo abrir el certificado. Revisa el archivo y su contraseña.");
    }
  }

  public String[] sunatCredentials(String documentNumber) {
    String credentials =
            companyStore
                    .findEncryptedCredentials(authOperations.accountId(), documentNumber)
                    .orElseThrow(
                            () ->
                                    new ResponseStatusException(
                                            SERVICE_UNAVAILABLE,
                                            "Configura las credenciales de envío antes de emitir."));
    String[] sol = secretVault.decrypt(credentials).split(":", 2);
    if (sol.length != 2 || sol[0].isBlank() || sol[1].isBlank())
      throw new ResponseStatusException(
              SERVICE_UNAVAILABLE, "Las credenciales SOL están incompletas.");
    return new String[]{documentNumber + sol[0], sol[1]};
  }
}
