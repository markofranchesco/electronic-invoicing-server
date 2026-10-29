package pe.invoicing.app.repository.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.invoicing.app.repository.identity.entity.AccountEntity;

import java.util.Optional;
import java.util.UUID;

public interface AccountEntityRepository extends JpaRepository<AccountEntity, UUID> {
  Optional<AccountEntity> findByEmailIgnoreCase(String email);
}
