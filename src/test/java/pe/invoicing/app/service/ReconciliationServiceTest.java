package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import pe.invoicing.app.service.document.impl.ReconciliationService;
import pe.invoicing.app.service.document.models.ReconciliationTarget;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("Conciliar con reserva exclusiva")
  void whenClaimUnavailableThenDoNotContactSunat() {
    // Otro proceso con reserva vigente impide un segundo envío automático.
    var document = issue("invoices/cash/request.json", "worker-busy");
    var worker = new ReconciliationService(store, documents, cancellations);
    when(store.claimReconciliation(ACCOUNT, COMPANY, document.getId())).thenReturn(Optional.empty());
    assertThat(worker.process(new ReconciliationTarget(ACCOUNT, COMPANY, document.getId()))).isFalse();
    verify(gateway, times(1)).sendBill(any(), any());
  }

  @Test
  @DisplayName("Reintento fallido conserva contexto")
  void whenReconciliationFailsThenRecordFailureAndRestoreIdentity() {
    // La falla queda programada para reintento sin dejar la identidad de empresa en el hilo.
    var document = issue("invoices/cash/request.json", "worker-failure");
    document.setStatus("UNKNOWN");
    UUID token = UUID.randomUUID();
    when(store.claimReconciliation(ACCOUNT, COMPANY, document.getId())).thenReturn(Optional.of(token));
    doThrow(new IllegalStateException("SUNAT no disponible")).when(gateway).consultBill(any());
    var previous = SecurityContextHolder.getContext();
    var worker = new ReconciliationService(store, documents, cancellations);
    worker.process(new ReconciliationTarget(ACCOUNT, COMPANY, document.getId()));
    assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
    verify(store).finishReconciliation(eq(ACCOUNT), eq(COMPANY), eq(document.getId()), eq(token), contains("SUNAT no disponible"));
    assertThat(document.getStatus()).isEqualTo("UNKNOWN");
  }
}
