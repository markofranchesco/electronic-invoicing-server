package pe.invoicing.app.config.sunat.properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public final class SunatProperties {
  private final URI emissionEndpoint;
  private final URI consultationEndpoint;
  private final String environment;

  public SunatProperties(
          @Value("${app.tax.emission-endpoint}") URI emissionEndpoint,
          @Value("${app.tax.consultation-endpoint}") URI consultationEndpoint,
          Environment profiles) {
    this.emissionEndpoint = emissionEndpoint;
    this.consultationEndpoint = consultationEndpoint;
    this.environment = profiles.acceptsProfiles(Profiles.of("production")) ? "PRODUCTION" : "BETA";
  }

  public URI emissionEndpoint() {
    return emissionEndpoint;
  }

  public URI consultationEndpoint() {
    return consultationEndpoint;
  }

  public boolean cdrConsultationAvailable() {
    String host = consultationEndpoint.getHost();
    if (host == null || host.equalsIgnoreCase("e-beta.sunat.gob.pe")) return false;
    if (host.toLowerCase(java.util.Locale.ROOT).endsWith(".sunat.gob.pe"))
      return consultationEndpoint.getPath().endsWith("/billConsultService");
    return true; // Adaptadores alternativos y servidores locales de pruebas.
  }

  public String environment() {
    return environment;
  }
}
