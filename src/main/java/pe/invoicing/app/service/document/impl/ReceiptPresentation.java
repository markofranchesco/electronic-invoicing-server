package pe.invoicing.app.service.document.impl;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReceiptPresentation {
  private final String publicBaseUrl;

  public ReceiptPresentation(@Value("${app.receipt.public-base-url:}") String publicBaseUrl) {
    this.publicBaseUrl = validate(publicBaseUrl);
  }

  private static String validate(String value) {
    if (value == null || value.isBlank()) return "";
    var uri = URI.create(value.trim());
    if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
            || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
      throw new IllegalArgumentException("La URL del comprobante debe ser HTTP/HTTPS, sin credenciales, consulta ni fragmento.");
    return value.trim().replaceAll("/+$", "");
  }

  public String documentUrl(String key) {
    return publicBaseUrl.isEmpty() || key == null || !key.matches("[a-f0-9]{32}") ? ""
            : publicBaseUrl + "/public/documents/" + key;
  }
}
