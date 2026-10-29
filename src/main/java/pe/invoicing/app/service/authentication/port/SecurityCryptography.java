package pe.invoicing.app.service.authentication.port;

import pe.invoicing.app.service.session.models.AuthPrincipal;

import java.util.UUID;

public interface SecurityCryptography {
  String generateToken(UUID accountId, UUID sessionId);

  AuthPrincipal verifyToken(String token);

  String sha256(String input);

  String sha256(byte[] input);

  String hashPassword(String password);

  boolean checkPassword(String candidate, String stored);
}
