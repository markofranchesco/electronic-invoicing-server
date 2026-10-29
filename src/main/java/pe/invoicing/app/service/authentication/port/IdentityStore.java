package pe.invoicing.app.service.authentication.port;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pe.invoicing.app.service.authentication.models.AccountDetails;
import pe.invoicing.app.service.authentication.models.Registration;
import pe.invoicing.app.service.session.models.SessionDetails;
import pe.invoicing.app.service.session.models.TokenDetails;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface IdentityStore {
  Page<SessionDetails> listSessions(UUID accountId, Pageable pageable);

  UUID registerAccount(Registration registration, String passwordHash);

  Optional<AccountDetails> findAccountByEmail(String email);

  void saveSession(TokenDetails session);

  Optional<TokenDetails> findSessionByHash(String hash);

  Optional<TokenDetails> findSessionById(UUID sessionId, UUID accountId);

  void touchSession(UUID sessionId, OffsetDateTime now);

  void deleteSession(UUID sessionId);
}
