package pe.invoicing.app.service.authentication.port;

public interface SecretVault {
  String encrypt(String raw);

  String decrypt(String encoded);
}
