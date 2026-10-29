package pe.invoicing.app.repository.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.invoicing.app.repository.document.entity.CounterEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CounterEntityRepository extends JpaRepository<CounterEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from CounterEntity c where c.id=:id")
  Optional<CounterEntity> lockById(@Param("id") UUID id);

  List<CounterEntity> findByTaxpayerId(UUID taxpayerId);
}
