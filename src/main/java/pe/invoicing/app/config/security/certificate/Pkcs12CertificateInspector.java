package pe.invoicing.app.config.security.certificate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.company.models.CertificateMetadata;
import pe.invoicing.app.service.company.port.CertificateInspector;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Enumeration;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

@Component
@RequiredArgsConstructor
public class Pkcs12CertificateInspector implements CertificateInspector {
  private final Clock clock;

  @Override
  public CertificateMetadata inspect(byte[] file, String password) {
    try {
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(new ByteArrayInputStream(file), password.toCharArray());
      Enumeration<String> aliases = ks.aliases();
      while (aliases.hasMoreElements()) {
        String alias = aliases.nextElement();
        if (!(ks.isKeyEntry(alias)
                && ks.getKey(alias, password.toCharArray()) instanceof PrivateKey
                && ks.getCertificate(alias) instanceof X509Certificate certificate)) continue;
        return new CertificateMetadata(
                certificate.getNotAfter().after(Date.from(clock.instant()))
                        && certificate.getNotBefore().before(Date.from(clock.instant())),
                certificate.getSubjectX500Principal().getName(),
                certificate.getNotAfter().toInstant().atOffset(ZoneOffset.UTC));
      }
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT,
              "El archivo debe contener un certificado X.509 con su clave privada.");
    } catch (GeneralSecurityException | IOException e) {
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT,
              "No pudimos abrir el certificado. Comprueba el archivo y su contraseña.");
    }
  }
}
