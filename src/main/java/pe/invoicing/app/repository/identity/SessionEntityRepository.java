package pe.invoicing.app.repository.identity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import pe.invoicing.app.repository.identity.entity.SessionEntity;

import java.util.Optional;
import java.util.UUID;

public interface SessionEntityRepository extends JpaRepository<SessionEntity, UUID> {
  Optional<SessionEntity> findByHashAndRevokedAtIsNull(String hash);

  Optional<SessionEntity> findByIdAndRevokedAtIsNull(UUID id);

  Optional<SessionEntity> findByIdAndAccountIdAndRevokedAtIsNull(UUID id, UUID accountId);

  Page<SessionEntity> findByAccountIdAndRevokedAtIsNull(UUID accountId, Pageable pageable);
}
