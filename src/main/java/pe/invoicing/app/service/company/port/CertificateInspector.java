package pe.invoicing.app.service.company.port;

import pe.invoicing.app.service.company.models.CertificateMetadata;

public interface CertificateInspector {
  CertificateMetadata inspect(byte[] file, String password);
}
