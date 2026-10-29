package pe.invoicing.app.repository.artifact;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.invoicing.app.repository.artifact.entity.DocumentArtifactEntity;

import java.util.UUID;

public interface DocumentArtifactEntityRepository
        extends JpaRepository<DocumentArtifactEntity, UUID> {
  boolean existsByTaxpayerId(UUID taxpayerId);
}
