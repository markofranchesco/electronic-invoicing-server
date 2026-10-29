package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.repository.document.DocumentEntityRepository;
import pe.invoicing.app.repository.document.impl.DocumentStoreImpl;
import pe.invoicing.app.repository.document.mappers.DocumentPersistenceMapper;
import pe.invoicing.app.repository.identity.TaxpayerEntityRepository;
import pe.invoicing.app.repository.identity.entity.TaxpayerEntity;
import pe.invoicing.app.service.cancellation.models.CancellationState;
import pe.invoicing.app.service.document.models.DocumentData;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentLifecycleServiceTest extends AbstractServiceTest {
  private DocumentStoreImpl adapter(DocumentData document) {
    var taxpayers = mock(TaxpayerEntityRepository.class);
    var repository = mock(DocumentEntityRepository.class);
    var persistence = Mappers.getMapper(DocumentPersistenceMapper.class);
    when(taxpayers.companyId(ACCOUNT, COMPANY)).thenReturn(ACCOUNT);
    when(taxpayers.lockCompany(ACCOUNT, COMPANY)).thenReturn(Optional.of(new TaxpayerEntity()));
    when(repository.findById(document.getId())).thenReturn(Optional.of(persistence.toEntity(document, ACCOUNT)));
    when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    return new DocumentStoreImpl(taxpayers, CLOCK, repository, series, artifacts, persistence);
  }

  @Test
  @DisplayName("Otorgamiento previo a baja")
  void whenDocumentGrantedBeforeClaimThenRejectCancellation() {
    // La prevalidación del servicio no sustituye la comprobación bajo el bloqueo existente.
    var document = issue("invoices/cash/request.json", "granted-before-claim");
    document.setGrantedAt(OffsetDateTime.now(CLOCK));
    assertThatThrownBy(() -> adapter(document).claimCancellation(ACCOUNT, COMPANY, document.getId(),
            CancellationState.builder().status("PENDING").build()))
            .isInstanceOf(ResponseStatusException.class);
    verify(series, never()).allocateNextNumber(any(), anyString(), anyString(), anyString());
  }

  @ParameterizedTest(name = "Estado {0}")
  @ValueSource(strings = {"UNKNOWN", "SUBMITTED", "CANCELLED", "REJECTED"})
  @DisplayName("Estado cambiado antes de baja")
  void whenInvoiceNoLongerAcceptedThenRejectCancellationClaim(String status) {
    // Releer dentro del bloqueo impide anular un estado que dejó de ser elegible.
    var document = issue("invoices/cash/request.json", "changed-before-claim");
    document.setStatus(status);
    assertThatThrownBy(() -> adapter(document).claimCancellation(ACCOUNT, COMPANY, document.getId(),
            CancellationState.builder().status("PENDING").build()))
            .isInstanceOf(ResponseStatusException.class);
  }

  @ParameterizedTest(name = "Baja activa {0}")
  @ValueSource(strings = {"PENDING", "SUBMITTED", "UNKNOWN", "MANUAL_REVIEW"})
  @DisplayName("Nota incompatible con baja")
  void whenCancellationUnresolvedThenDoNotReserveCreditNote(String status) {
    // Una baja sin resultado impide crear una nota total competidora.
    var original = issue("invoices/cash/request.json", "note-void-conflict");
    original.setCancellation(CancellationState.builder().status(status).build());
    var note = original.toBuilder().id(UUID.randomUUID()).sourceDocumentId(original.getId())
            .documentType("07").status("DRAFT").build();
    assertThatThrownBy(() -> adapter(original).createIssuance(ACCOUNT, note, "new-key", "new-body"))
            .isInstanceOf(ResponseStatusException.class);
    verify(series, never()).allocateNextNumber(any(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("Baja antes de otorgamiento")
  void whenCancellationClaimedThenRejectGrant() {
    // Otorgar y dar de baja se excluyen mediante el mismo bloqueo de empresa.
    var document = issue("invoices/cash/request.json", "void-before-grant");
    document.setCancellation(CancellationState.builder().status("SUBMITTED").build());
    assertThatThrownBy(() -> adapter(document).claimGrant(ACCOUNT, COMPANY, document.getId()))
            .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("Otorgar tras baja rechazada")
  void whenCancellationRejectedThenAllowGrantOfAcceptedDocument() {
    // Un intento de baja rechazado no extingue la aceptación del comprobante.
    var document = issue("invoices/cash/request.json", "rejected-void-grant");
    document.setCancellation(CancellationState.builder().status("REJECTED").build());
    assertThat(adapter(document).claimGrant(ACCOUNT, COMPANY, document.getId()).getGrantedAt())
            .isEqualTo(OffsetDateTime.now(CLOCK));
  }

  @Test
  @DisplayName("Conservar otorgamiento inicial")
  void whenGrantRepeatedThenKeepOriginalTimestamp() {
    // La declaración repetida no cambia la fecha del primer otorgamiento registrado.
    var document = issue("invoices/cash/request.json", "repeat-grant-date");
    var first = OffsetDateTime.now(CLOCK).minusMinutes(5);
    document.setGrantedAt(first);
    assertThat(adapter(document).claimGrant(ACCOUNT, COMPANY, document.getId()).getGrantedAt()).isEqualTo(first);
  }

  @Test
  @DisplayName("Publicación sin CDR requerida")
  void whenAcceptedInvoiceHasNoCdrThenDoNotPublishUnderLock() {
    // La comprobación definitiva utiliza los archivos persistidos del comprobante aceptado.
    var document = issue("invoices/cash/request.json", "publish-no-cdr");
    files.remove(document.getId() + ":CDR");
    assertThatThrownBy(() -> adapter(document).publishDocument(ACCOUNT, COMPANY, document.getId(), "a".repeat(32)))
            .isInstanceOf(ResponseStatusException.class);
    assertThat(document.getGrantedAt()).isNull();
  }

  @Test
  @DisplayName("Rechazar guardado obsoleto")
  void whenDocumentRevisionChangedThenDoNotOverwriteNewerState() {
    // La respuesta tardía no puede guardar una copia anterior a la revisión persistida.
    var document = issue("invoices/cash/request.json", "stale-document-save");
    document.setVersion(3L);
    var adapter = adapter(document);
    var stale = document.toBuilder().version(2L).status("CANCELLED").build();
    assertThatThrownBy(() -> adapter.saveDocument(ACCOUNT, stale))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                    failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));
    assertThat(document.getStatus()).isEqualTo("ACCEPTED");
  }

  @Test
  @DisplayName("Otorgar implica publicación efectiva")
  void whenGrantRecordedThenCreatePublicationInSameOperation() {
    // La ruta histórica de otorgamiento también deja disponibles los archivos del adquirente.
    var document = issue("invoices/cash/request.json", "grant-publishes");
    var granted = adapter(document).claimGrant(ACCOUNT, COMPANY, document.getId());
    assertThat(granted.getPublicationKey()).matches("[a-f0-9]{32}");
    assertThat(granted.getGrantedAt()).isNotNull();
  }
}
