package pe.invoicing.app.config.sunat.models;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

public record SigningMaterial(PrivateKey key, X509Certificate certificate) {
}
