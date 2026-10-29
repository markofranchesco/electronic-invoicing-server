package pe.invoicing.app.repository.artifact;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.invoicing.app.repository.artifact.entity.IssueOperationEntity;

import java.util.Optional;
import java.util.UUID;

public interface IssueOperationEntityRepository extends JpaRepository<IssueOperationEntity, UUID> {
  boolean existsByTaxpayerId(UUID taxpayerId);

  Optional<IssueOperationEntity> findByTaxpayerIdAndKeyHash(UUID taxpayerId, String keyHash);
}
