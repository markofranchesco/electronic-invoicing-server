package pe.invoicing.app.repository.identity.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.repository.artifact.DocumentArtifactEntityRepository;
import pe.invoicing.app.repository.artifact.IssueOperationEntityRepository;
import pe.invoicing.app.repository.document.CounterEntityRepository;
import pe.invoicing.app.repository.document.DocumentEntityRepository;
import pe.invoicing.app.repository.document.entity.CounterEntity;
import pe.invoicing.app.repository.identity.TaxpayerEntityRepository;
import pe.invoicing.app.repository.identity.entity.TaxpayerEntity;
import pe.invoicing.app.repository.identity.mappers.CompanyPersistenceMapper;
import pe.invoicing.app.service.company.models.Company;
import pe.invoicing.app.service.company.models.CompanyData;
import pe.invoicing.app.service.company.port.CompanyStore;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class CompanyStoreImpl implements CompanyStore {
  private final TaxpayerEntityRepository taxpayers;
  private final DocumentEntityRepository documents;
  private final CounterEntityRepository counters;
  private final DocumentArtifactEntityRepository artifacts;
  private final IssueOperationEntityRepository operations;
  private final CompanyPersistenceMapper mapper;

  @Override
  public Company getCompany(UUID accountId, String documentNumber) {
    return mapper.toCompany(
            taxpayers
                    .findByAccountIdAndDocumentNumber(accountId, documentNumber)
                    .orElseThrow(
                            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada.")));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Company saveCompany(
          UUID accountId,
          String documentNumber,
          CompanyData data,
          String encryptedCertificate,
          String encryptedCredentials) {
    TaxpayerEntity entity =
            taxpayers
                    .findByAccountIdAndDocumentNumber(accountId, documentNumber)
                    .orElseGet(
                            () ->
                                    TaxpayerEntity.builder()
                                            .id(UUID.randomUUID())
                                            .accountId(accountId)
                                            .documentNumber(documentNumber)
                                            .createdAt(OffsetDateTime.now())
                                            .build());
    mapper.update(data, encryptedCertificate, encryptedCredentials, entity);
    entity.setUpdatedAt(OffsetDateTime.now());
    return mapper.toCompany(taxpayers.save(entity));
  }

  @Override
  public Optional<String> findEncryptedCertificate(UUID accountId, String documentNumber) {
    return taxpayers
            .findByAccountIdAndDocumentNumber(accountId, documentNumber)
            .map(TaxpayerEntity::getEncryptedCertificate);
  }

  @Override
  public Optional<String> findEncryptedCredentials(UUID accountId, String documentNumber) {
    return taxpayers
            .findByAccountIdAndDocumentNumber(accountId, documentNumber)
            .map(TaxpayerEntity::getEncryptedCredentials);
  }

  @Override
  public Page<Company> listCompanies(UUID accountId, Pageable pageable) {
    return taxpayers.findByAccountId(accountId, pageable).map(mapper::toCompany);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void deleteCompany(UUID accountId, String documentNumber) {
    TaxpayerEntity company =
            taxpayers
                    .findByAccountIdAndDocumentNumber(accountId, documentNumber)
                    .orElseThrow(
                            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada."));
    UUID companyId = company.getId();
    List<CounterEntity> companyCounters = counters.findByTaxpayerId(companyId);
    if (documents.existsByTaxpayerId(companyId)
            || companyCounters.stream().anyMatch(counter -> counter.getNextNumber() > 1)
            || artifacts.existsByTaxpayerId(companyId)
            || operations.existsByTaxpayerId(companyId)) {
      throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "La empresa tiene documentos, series, archivos u operaciones asociados.");
    }
    counters.deleteAll(companyCounters);
    taxpayers.delete(company);
  }
}
