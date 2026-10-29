package pe.invoicing.app.service.company.models;

import lombok.Builder;

@Builder
public record CertificateBundle(String content, String password) {
  public static CertificateBundle parse(String stored) {
    if (!stored.startsWith("v2:")) {
      throw new IllegalArgumentException("Formato del certificado almacenado no compatible.");
    }
    int split = stored.indexOf(':', 3);
    if (split < 0) {
      throw new IllegalArgumentException("Certificado almacenado incompleto.");
    }
    int length;
    try {
      length = Integer.parseInt(stored.substring(3, split));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
              "Longitud del certificado almacenado inválida.", exception);
    }
    int start = split + 1;
    if (length < 1 || length > stored.length() - start) {
      throw new IllegalArgumentException("Contenido del certificado almacenado inválido.");
    }
    return new CertificateBundle(
            stored.substring(start, start + length), stored.substring(start + length));
  }

  public String serialize() {
    return "v2:" + content.length() + ":" + content + password;
  }
}
