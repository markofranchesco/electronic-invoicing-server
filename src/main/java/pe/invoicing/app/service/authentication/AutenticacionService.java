package pe.invoicing.app.service.authentication;

import pe.invoicing.app.service.authentication.models.Login;
import pe.invoicing.app.service.authentication.models.Registration;
import pe.invoicing.app.service.session.models.AuthPrincipal;
import pe.invoicing.app.service.session.models.SessionClient;

import java.util.UUID;

public interface AutenticacionService {
  AuthPrincipal currentPrincipal();

  UUID accountId();

  String signUp(Registration registration, SessionClient client);

  String signIn(Login login, SessionClient client);

  void signOut();

  AuthPrincipal authenticateBearerToken(String token);
}
