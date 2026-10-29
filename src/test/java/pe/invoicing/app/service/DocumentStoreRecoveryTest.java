package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import pe.invoicing.app.repository.document.DocumentEntityRepository;
import pe.invoicing.app.repository.document.impl.DocumentStoreImpl;
import pe.invoicing.app.repository.document.mappers.DocumentPersistenceMapper;
import pe.invoicing.app.repository.identity.TaxpayerEntityRepository;
import pe.invoicing.app.repository.identity.entity.TaxpayerEntity;
import pe.invoicing.app.service.cancellation.models.CancellationState;
import pe.invoicing.app.service.document.models.DocumentData;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentStoreRecoveryTest extends AbstractServiceTest {
  private DocumentStoreImpl adapter(DocumentData document) {
    var taxpayers = mock(TaxpayerEntityRepository.class);
    var repository = mock(DocumentEntityRepository.class);
    var persistence = Mappers.getMapper(DocumentPersistenceMapper.class);
    when(taxpayers.companyId(ACCOUNT, COMPANY)).thenReturn(ACCOUNT);
    when(taxpayers.lockCompany(ACCOUNT, COMPANY)).thenReturn(Optional.of(new TaxpayerEntity()));
    when(taxpayers.lockCompany(ACCOUNT, COMPANY)).thenReturn(Optional.of(new TaxpayerEntity()));
    when(repository.findById(document.getId())).thenReturn(Optional.of(persistence.toEntity(document, ACCOUNT)));
    when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    return new DocumentStoreImpl(taxpayers, CLOCK, repository, series, artifacts, persistence);
  }

  @Test
  @DisplayName("Activación explícita libera pendiente; reservar no emite")
  void heldRequiresExplicitActivation() {
    var result = documents.savePendingDocument(COMPANY, "held-store-release", draft("receipts/cash/request.json"));
    var document = saved.get(result.getDocumentId());
    assertThat(adapter(document).claimEmission(ACCOUNT, COMPANY, document.getId())).isEmpty();
    var activated = adapter(document).requestEmission(ACCOUNT, COMPANY, document.getId());
    assertThat(activated.getStatus()).isEqualTo("DRAFT");
    assertThat(activated.getId()).isEqualTo(document.getId());
    assertThat(activated.getNumber()).isEqualTo(document.getNumber());
    assertThat(adapter(activated).requestEmission(ACCOUNT, COMPANY, activated.getId()).getStatus()).isEqualTo("DRAFT");
  }

  @Test
  @DisplayName("Conservar CDR de rectificación rechazada")
  void whenRejectedCorrectionArchivedThenDoNotReplaceItsCdrWithPreviousAcceptance() {
    // La CDR genérica conserva la aceptación, mientras la histórica corresponde al RC rechazado.
    DocumentData document = issue("receipts/cash/request.json", "archive-rejected");
    document.setStatus("ACCEPTED");
    document.getCommunication().setStatus("REJECTED");
    String reference = document.getCommunication().getBatchReference();
    files.put(document.getId() + ":CDR", new byte[]{1});
    files.put(document.getId() + ":CDR:" + reference, new byte[]{2});
    adapter(document).claimSummaryReport(ACCOUNT, COMPANY, document.getId(), reference);
    assertThat(files.get(document.getId() + ":CDR:" + reference)).containsExactly((byte) 2);
  }

  @Test
  @DisplayName("Recuperar preparación de baja huérfana")
  void whenCancellationPendingWithoutXmlThenAllowRecoveryAfterLease() {
    // Una caída anterior a la firma deja PENDING sin ticket ni XML.
    DocumentData document = issue("invoices/cash/request.json", "pending-void");
    document.setCancellation(CancellationState.builder().status("PENDING")
            .lastAttemptAt(java.time.OffsetDateTime.now(CLOCK).minusMinutes(3)).build());
    assertThat(adapter(document).claimRecovery(ACCOUNT, COMPANY, document.getId(), true)).isPresent();
  }

  @Test
  @DisplayName("Conservar versión al guardar")
  void whenDocumentSavedThenCarryVersionForConcurrentUpdateDetection() {
    // La respuesta SUNAT no puede sobrescribir silenciosamente un otorgamiento guardado entretanto.
    var document = issue("invoices/cash/request.json", "version-save");
    document.setVersion(7L);
    var taxpayers = mock(TaxpayerEntityRepository.class);
    var repository = mock(DocumentEntityRepository.class);
    var persistence = Mappers.getMapper(DocumentPersistenceMapper.class);
    when(taxpayers.companyId(ACCOUNT, COMPANY)).thenReturn(ACCOUNT);
    when(taxpayers.lockCompany(ACCOUNT, COMPANY)).thenReturn(Optional.of(new TaxpayerEntity()));
    when(repository.findById(document.getId())).thenReturn(Optional.of(persistence.toEntity(document, ACCOUNT)));
    when(repository.saveAndFlush(any())).thenAnswer(c -> {
      pe.invoicing.app.repository.document.entity.DocumentEntity entity = c.getArgument(0);
      assertThat(persistence.toModel(entity).getVersion()).isEqualTo(8L);
      return entity;
    });
    var adapter = new DocumentStoreImpl(taxpayers, CLOCK, repository, series, artifacts, persistence);
    assertThat(adapter.saveDocument(ACCOUNT, document).getVersion()).isEqualTo(8L);
    assertThat(document.getVersion()).isEqualTo(8L);
  }
}
