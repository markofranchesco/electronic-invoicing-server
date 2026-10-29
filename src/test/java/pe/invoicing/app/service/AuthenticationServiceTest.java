package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.config.security.crypto.Crypto;
import pe.invoicing.app.service.authentication.models.AccountDetails;
import pe.invoicing.app.service.authentication.models.Login;
import pe.invoicing.app.service.authentication.models.Registration;
import pe.invoicing.app.service.session.models.SessionClient;
import pe.invoicing.app.service.session.models.TokenDetails;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticationServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("Registro con hash")
  void whenSigningUpThenStoreHashAndIssueRealJwt() {
    // Ni contraseña ni token se guardan en claro.
    when(identities.registerAccount(any(), anyString())).thenReturn(ACCOUNT);
    String token =
            authentication.signUp(
                    new Registration("ana.demo@example.com", "ClaveDemoSegura2026!"),
                    SessionClient.builder().build());
    ArgumentCaptor<String> password = ArgumentCaptor.forClass(String.class);
    verify(identities).registerAccount(any(), password.capture());
    assertThat(crypto.checkPassword("ClaveDemoSegura2026!", password.getValue())).isTrue();
    assertThat(crypto.verifyToken(token).accountId()).isEqualTo(ACCOUNT);
    ArgumentCaptor<TokenDetails> session = ArgumentCaptor.forClass(TokenDetails.class);
    verify(identities).saveSession(session.capture());
    assertThat(session.getValue().hash()).isEqualTo(crypto.sha256(token));
  }

  @Test
  @DisplayName("Acceso válido")
  void whenPasswordMatchesThenCreateSession() {
    // Se usa PBKDF y firma JWT reales.
    Login login = fixture("authentication/login/request.json", Login.class);
    when(identities.findAccountByEmail(login.email()))
            .thenReturn(
                    Optional.of(
                            AccountDetails.builder()
                                    .id(ACCOUNT)
                                    .passwordHash(crypto.hashPassword(login.password()))
                                    .build()));
    assertThat(
            crypto
                    .verifyToken(authentication.signIn(login, SessionClient.builder().build()))
                    .accountId())
            .isEqualTo(ACCOUNT);
  }

  @Test
  @DisplayName("Contraseña incorrecta")
  void whenPasswordDiffersThenDenySession() {
    // Las credenciales incorrectas no generan sesiones.
    when(identities.findAccountByEmail(anyString()))
            .thenReturn(
                    Optional.of(
                            AccountDetails.builder()
                                    .id(ACCOUNT)
                                    .passwordHash(crypto.hashPassword("AnotherSecret2026!"))
                                    .build()));
    assertThatThrownBy(
            () ->
                    authentication.signIn(
                            fixture("authentication/login/request.json", Login.class),
                            SessionClient.builder().build()))
            .isInstanceOf(ResponseStatusException.class);
    verify(identities, never()).saveSession(any());
  }

  @Test
  @DisplayName("Sesión revocada")
  void whenJwtHasNoStoredSessionThenReject() {
    // Una firma válida no basta si la sesión fue eliminada.
    assertThatThrownBy(
            () ->
                    authentication.authenticateBearerToken(
                            crypto.generateToken(ACCOUNT, UUID.randomUUID())))
            .isInstanceOf(ResponseStatusException.class);
    verify(identities, never()).touchSession(any(), any());
  }

  @Test
  @DisplayName("JWT manipulado")
  void whenSignatureChangesThenRejectBeforePersistence() {
    // El token manipulado se rechaza mediante el verificador real.
    String token = crypto.generateToken(ACCOUNT, UUID.randomUUID());
    assertThatThrownBy(
            () ->
                    authentication.authenticateBearerToken(
                            token.substring(0, token.lastIndexOf('.') + 1)
                                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
            .isInstanceOf(JwtException.class);
    verify(identities, never()).findSessionByHash(anyString());
  }

  @Test
  @DisplayName("Sesión de otra cuenta")
  void whenStoredSessionBelongsToAnotherAccountThenReject() {
    // La identidad del JWT debe coincidir con la sesión persistida.
    UUID session = UUID.randomUUID();
    String token = crypto.generateToken(ACCOUNT, session);
    when(identities.findSessionByHash(crypto.sha256(token)))
            .thenReturn(
                    Optional.of(TokenDetails.builder().id(session).accountId(UUID.randomUUID()).build()));
    assertThatThrownBy(() -> authentication.authenticateBearerToken(token))
            .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("Revocación aislada")
  void whenRevokingSessionThenScopeLookupToCurrentAccount() {
    // No se borra una sesión que el puerto no devuelve para esta cuenta.
    UUID session = UUID.randomUUID();
    assertThatThrownBy(() -> sessions.revokeSession(session))
            .isInstanceOf(ResponseStatusException.class);
    verify(identities).findSessionById(session, ACCOUNT);
    verify(identities, never()).deleteSession(any());
  }

  @Test
  @DisplayName("Cerrar sesión")
  void whenSigningOutThenDeleteCurrentSession() {
    // Solo se revoca la sesión usada en esta operación.
    authentication.signOut();
    verify(identities).deleteSession(principal.sessionId());
  }

  @Test
  @DisplayName("Cifrado autenticado")
  void whenProtectingCredentialsThenRoundTripAndRandomizeCiphertext() {
    // AES-GCM conserva el secreto y utiliza un IV distinto.
    assertThat(vault.decrypt(vault.encrypt("SOL:secret"))).isEqualTo("SOL:secret");
    assertThat(vault.encrypt("SOL:secret")).isNotEqualTo(vault.encrypt("SOL:secret"));
  }

  @Test
  @DisplayName("JWT vencido")
  void whenTokenExpiresThenRejectSignatureValidToken() {
    // Se avanza el reloj sin esperar y sin sustituir el verificador JWT.
    String token = crypto.generateToken(ACCOUNT, UUID.randomUUID());
    assertThatThrownBy(
            () ->
                    new Crypto(
                            "service-tests-secret-2026-not-production",
                            Clock.offset(CLOCK, Duration.ofHours(13)))
                            .verifyToken(token))
            .isInstanceOf(JwtException.class);
  }

  @Test
  @DisplayName("Sesión válida")
  void whenJwtMatchesStoredSessionThenUpdateLastAccess() {
    // El hash y la identidad se contrastan antes de actualizar la sesión.
    String token = crypto.generateToken(ACCOUNT, principal.sessionId());
    when(identities.findSessionByHash(crypto.sha256(token)))
            .thenReturn(
                    Optional.of(
                            TokenDetails.builder().id(principal.sessionId()).accountId(ACCOUNT).build()));
    assertThat(authentication.authenticateBearerToken(token)).isEqualTo(principal);
    verify(identities).touchSession(principal.sessionId(), OffsetDateTime.now(CLOCK));
  }
}
