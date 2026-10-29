package pe.invoicing.app.repository.identity;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.repository.identity.entity.TaxpayerEntity;

import java.util.Optional;
import java.util.UUID;

public interface TaxpayerEntityRepository extends JpaRepository<TaxpayerEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
          "select t from TaxpayerEntity t where t.accountId = :accountId and t.documentNumber ="
                  + " :documentNumber")
  Optional<TaxpayerEntity> lockCompany(
          @Param("accountId") UUID accountId, @Param("documentNumber") String documentNumber);

  Page<TaxpayerEntity> findByAccountId(UUID accountId, Pageable pageable);

  Optional<TaxpayerEntity> findByAccountIdAndDocumentNumber(UUID accountId, String documentNumber);

  default UUID companyId(UUID accountId, String documentNumber) {
    return findByAccountIdAndDocumentNumber(accountId, documentNumber)
            .map(TaxpayerEntity::getId)
            .orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada."));
  }
}
