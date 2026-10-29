package pe.invoicing.app.repository.identity.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.repository.identity.AccountEntityRepository;
import pe.invoicing.app.repository.identity.SessionEntityRepository;
import pe.invoicing.app.repository.identity.entity.AccountEntity;
import pe.invoicing.app.repository.identity.mappers.IdentityPersistenceMapper;
import pe.invoicing.app.service.authentication.models.AccountDetails;
import pe.invoicing.app.service.authentication.models.Registration;
import pe.invoicing.app.service.authentication.port.IdentityStore;
import pe.invoicing.app.service.session.models.SessionDetails;
import pe.invoicing.app.service.session.models.TokenDetails;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IdentityStoreImpl implements IdentityStore {
  private final AccountEntityRepository accounts;
  private final SessionEntityRepository sessions;
  private final IdentityPersistenceMapper mapper;

  @Override
  public Page<SessionDetails> listSessions(UUID accountId, Pageable pageable) {
    return sessions.findByAccountIdAndRevokedAtIsNull(accountId, pageable).map(mapper::toSession);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public UUID registerAccount(Registration registration, String hash) {
    String email = registration.email().strip().toLowerCase(Locale.ROOT);
    if (accounts.findByEmailIgnoreCase(email).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "El correo ya está registrado.");
    }
    return accounts
            .save(
                    AccountEntity.builder()
                            .id(UUID.randomUUID())
                            .email(email)
                            .password(hash)
                            .createdAt(OffsetDateTime.now())
                            .build())
            .getId();
  }

  @Override
  public Optional<AccountDetails> findAccountByEmail(String email) {
    return accounts.findByEmailIgnoreCase(email).map(mapper::toDetails);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void saveSession(TokenDetails input) {
    sessions.save(mapper.toEntity(input));
  }

  @Override
  public Optional<TokenDetails> findSessionByHash(String hash) {
    return sessions.findByHashAndRevokedAtIsNull(hash).map(mapper::toToken);
  }

  @Override
  public Optional<TokenDetails> findSessionById(UUID id, UUID accountId) {
    return sessions.findByIdAndAccountIdAndRevokedAtIsNull(id, accountId).map(mapper::toToken);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void touchSession(UUID id, OffsetDateTime now) {
    sessions.findByIdAndRevokedAtIsNull(id).ifPresent(session -> session.setLastUsedAt(now));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void deleteSession(UUID id) {
    sessions
            .findByIdAndRevokedAtIsNull(id)
            .ifPresent(session -> session.setRevokedAt(OffsetDateTime.now()));
  }
}
