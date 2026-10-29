package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import pe.invoicing.app.repository.document.DocumentEntityRepository;
import pe.invoicing.app.repository.document.entity.DocumentEntity;
import pe.invoicing.app.repository.document.impl.DocumentStoreImpl;
import pe.invoicing.app.repository.document.mappers.DocumentPersistenceMapper;
import pe.invoicing.app.repository.identity.TaxpayerEntityRepository;
import pe.invoicing.app.repository.identity.entity.TaxpayerEntity;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.ReconciliationState;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReconciliationPolicyServiceTest extends AbstractServiceTest {
  private final DocumentPersistenceMapper persistence = Mappers.getMapper(DocumentPersistenceMapper.class);
  private DocumentEntity persisted;

  private DocumentStoreImpl adapter(DocumentData document) {
    var taxpayers = mock(TaxpayerEntityRepository.class);
    var repository = mock(DocumentEntityRepository.class);
    persisted = persistence.toEntity(document, ACCOUNT);
    when(taxpayers.companyId(ACCOUNT, COMPANY)).thenReturn(ACCOUNT);
    when(taxpayers.lockCompany(ACCOUNT, COMPANY)).thenReturn(Optional.of(new TaxpayerEntity()));
    when(repository.findById(document.getId())).thenAnswer(c -> Optional.of(persisted));
    when(repository.saveAndFlush(any())).thenAnswer(c -> {
      persisted = c.getArgument(0);
      return persisted;
    });
    return new DocumentStoreImpl(taxpayers, CLOCK, repository, series, artifacts, persistence);
  }

  @ParameterizedTest(name = "Reserva {0}")
  @ValueSource(strings = {"lease", "future", "manual", "exhausted"})
  @DisplayName("Respetar política de conciliación")
  void whenRetryNotEligibleThenDoNotClaim(String reason) {
    // Las reservas vigentes, la espera y la intervención manual no pueden saltarse.
    var document = issue("invoices/cash/request.json", "policy-not-due");
    document.setStatus("UNKNOWN");
    var state = new ReconciliationState();
    switch (reason) {
      case "lease" -> state.setLeaseUntil(OffsetDateTime.now(CLOCK).plusMinutes(1));
      case "future" -> state.setNextAttemptAt(OffsetDateTime.now(CLOCK).plusSeconds(30));
      case "manual" -> state.setManualReview(true);
      default -> state.setAttempts(8);
    }
    document.setReconciliation(state);
    assertThat(adapter(document).claimReconciliation(ACCOUNT, COMPANY, document.getId())).isEmpty();
  }

  @Test
  @DisplayName("Reserva vencida y respuesta antigua")
  void whenLeaseExpiredThenNewTokenRejectsStaleCompletion() {
    // Un trabajador atrasado no puede completar la reserva de otro trabajador.
    var document = issue("invoices/cash/request.json", "policy-expired");
    document.setStatus("UNKNOWN");
    var state = new ReconciliationState();
    var old = UUID.randomUUID();
    state.setLeaseToken(old);
    state.setLeaseUntil(OffsetDateTime.now(CLOCK).minusMinutes(1));
    document.setReconciliation(state);
    var store = adapter(document);
    var token = store.claimReconciliation(ACCOUNT, COMPANY, document.getId()).orElseThrow();
    store.finishReconciliation(ACCOUNT, COMPANY, document.getId(), old, null);
    assertThat(persistence.toModel(persisted).getReconciliation().getLeaseToken()).isEqualTo(token);
    store.finishReconciliation(ACCOUNT, COMPANY, document.getId(), token, "RETRY:timeout");
    var result = persistence.toModel(persisted);
    assertThat(result.getReconciliation().getNextAttemptAt()).isAfter(OffsetDateTime.now(CLOCK));
    assertThat(result.getStatus()).isEqualTo("UNKNOWN");
  }

  @Test
  @DisplayName("Agotar intentos sin rechazo fiscal")
  void whenLastAttemptFailsThenRequireInterventionWithoutRejectingDocument() {
    // Agotar ocho intentos modifica la operativa, no el resultado tributario.
    var document = issue("invoices/cash/request.json", "policy-exhausted");
    document.setStatus("UNKNOWN");
    var state = new ReconciliationState();
    state.setAttempts(7);
    document.setReconciliation(state);
    var store = adapter(document);
    var token = store.claimReconciliation(ACCOUNT, COMPANY, document.getId()).orElseThrow();
    store.finishReconciliation(ACCOUNT, COMPANY, document.getId(), token, "RETRY:timeout");
    var result = persistence.toModel(persisted);
    assertThat(result.getReconciliation().isManualReview()).isTrue();
    assertThat(result.getStatus()).isEqualTo("UNKNOWN");
    store.resumeReconciliation(ACCOUNT, COMPANY, document.getId());
    assertThat(store.claimReconciliation(ACCOUNT, COMPANY, document.getId())).isPresent();
  }

  @Test
  @DisplayName("No reabrir conciliación activa")
  void whenWorkerLeaseActiveThenRejectManualResume() {
    // Reiniciar intentos no debe quitar la reserva de un envío que sigue en ejecución.
    var document = issue("invoices/cash/request.json", "resume-active-lease");
    document.setStatus("UNKNOWN");
    var state = new ReconciliationState();
    state.setLeaseUntil(OffsetDateTime.now(CLOCK).plusMinutes(5));
    document.setReconciliation(state);
    assertThatThrownBy(() -> adapter(document).resumeReconciliation(ACCOUNT, COMPANY, document.getId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  @DisplayName("No conciliar estados finales")
  void whenDocumentAcceptedWithoutPendingCancellationThenDoNotClaim() {
    // La ejecución periódica no vuelve a transmitir comprobantes concluidos.
    var document = issue("invoices/cash/request.json", "worker-terminal");
    assertThat(adapter(document).claimReconciliation(ACCOUNT, COMPANY, document.getId())).isEmpty();
  }
}
