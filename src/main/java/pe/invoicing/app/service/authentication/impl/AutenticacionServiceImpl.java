package pe.invoicing.app.service.authentication.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.authentication.AutenticacionService;
import pe.invoicing.app.service.authentication.models.AccountDetails;
import pe.invoicing.app.service.authentication.models.Login;
import pe.invoicing.app.service.authentication.models.Registration;
import pe.invoicing.app.service.authentication.port.IdentityStore;
import pe.invoicing.app.service.authentication.port.SecurityCryptography;
import pe.invoicing.app.service.session.models.AuthPrincipal;
import pe.invoicing.app.service.session.models.SessionClient;
import pe.invoicing.app.service.session.models.TokenDetails;
import pe.invoicing.app.service.session.port.RequestIdentity;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
@RequiredArgsConstructor
public class AutenticacionServiceImpl implements AutenticacionService {
  private final SecurityCryptography securityCryptography;
  private final Clock clock;
  private final IdentityStore identityStore;
  private final RequestIdentity requestIdentity;

  @Override
  public AuthPrincipal currentPrincipal() {
    return requestIdentity.currentPrincipal();
  }

  @Override
  public UUID accountId() {
    return currentPrincipal().accountId();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public String signUp(Registration registration, SessionClient client) {
    UUID accountId =
            identityStore.registerAccount(
                    registration, securityCryptography.hashPassword(registration.password()));
    return createLoginSession(accountId, client);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public String signIn(Login login, SessionClient client) {
    AccountDetails account =
            identityStore
                    .findAccountByEmail(login.email().strip().toLowerCase(Locale.ROOT))
                    .orElseThrow(
                            () -> new ResponseStatusException(UNAUTHORIZED, "Credenciales incorrectas."));
    if (!securityCryptography.checkPassword(login.password(), account.passwordHash())) {
      throw new ResponseStatusException(UNAUTHORIZED, "Credenciales incorrectas.");
    }
    return createLoginSession(account.id(), client);
  }

  private String createLoginSession(UUID accountId, SessionClient client) {
    UUID sessionId = UUID.randomUUID();
    String accessToken = securityCryptography.generateToken(accountId, sessionId);
    identityStore.saveSession(
            TokenDetails.builder()
                    .id(sessionId)
                    .accountId(accountId)
                    .hash(securityCryptography.sha256(accessToken))
                    .createdAt(OffsetDateTime.now(clock))
                    .client(client)
                    .build());
    return accessToken;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void signOut() {
    AuthPrincipal session = currentPrincipal();
    identityStore.deleteSession(session.sessionId());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AuthPrincipal authenticateBearerToken(String rawToken) {
    AuthPrincipal principal = securityCryptography.verifyToken(rawToken);
    TokenDetails session = identityStore.findSessionByHash(securityCryptography.sha256(rawToken))
            .filter(found -> principal.accountId().equals(found.accountId()) && principal.sessionId().equals(found.id()))
            .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Tu sesión fue revocada o no existe."));
    identityStore.touchSession(session.id(), OffsetDateTime.now(clock));
    return AuthPrincipal.builder().sessionId(session.id()).accountId(session.accountId()).build();
  }
}
