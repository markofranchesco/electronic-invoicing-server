package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.invoicing.app.service.cancellation.models.CancellationDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DurableCancellationServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("Baja aceptada localmente sobrevive antes de preparar XML")
  void whenCancellationAcceptedThenWorkerResumesSameReference() {
    var document = issue("invoices/cash/request.json", "durable-void-origin");
    var request = new CancellationDetails("Venta no otorgada", "COMMUNICATION_BAJA", true);
    var accepted = cancellations.acceptCancellation(COMPANY, document.getId(), request);
    var reference = accepted.getReference();
    assertThat(accepted.getStatus()).isEqualTo("PENDING");
    verify(gateway, never()).sendSummary(anyString(), anyString(), any());
    assertThat(cancellations.acceptCancellation(COMPANY, document.getId(), request).getReference()).isEqualTo(reference);
    verify(store, times(1)).claimCancellation(any(), anyString(), any(), any());
    // Primer ciclo identifica preparación interrumpida; el siguiente continúa la misma baja.
    assertThat(cancellations.recoverCancellation(COMPANY, document.getId(), null).getStatus()).isEqualTo("PREPARATION_FAILED");
    var sent = cancellations.recoverCancellation(COMPANY, document.getId(), null);
    assertThat(sent.getStatus()).isEqualTo("SUBMITTED");
    assertThat(sent.getReference()).isEqualTo(reference);
    verify(gateway, times(1)).sendSummary(eq(COMPANY), eq(reference), any());
    assertThat(document.getStatus()).isEqualTo("ACCEPTED");
  }
}
