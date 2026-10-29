package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.cancellation.models.CancellationState;
import pe.invoicing.app.service.creditnote.models.CreditNoteDraft;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CreditNoteBoundaryServiceTest extends AbstractServiceTest {
  @ParameterizedTest(name = "Campo {0}")
  @ValueSource(strings = {"body", "reasonCode", "reason", "series", "date", "blank"})
  @DisplayName("Nota incompleta")
  void whenNoteInputIncompleteThenRejectBeforeCreatingDocument(String field) {
    // La nota interna tampoco puede saltar validaciones por no usar un DTO HTTP.
    var original = issue("invoices/cash/request.json", "invalid-note-original");
    CreditNoteDraft request = "body".equals(field) ? null : new CreditNoteDraft(
            "reasonCode".equals(field) ? null : "01",
            "reason".equals(field) ? null : "blank".equals(field) ? " " : "Anulación total",
            "series".equals(field) ? null : "FC01",
            "date".equals(field) ? null : original.getIssueDate(), null);
    assertThatThrownBy(() -> notes.issue(COMPANY, original.getId(), "invalid-note-input", request))
            .isInstanceOf(ResponseStatusException.class);
    verify(store, times(1)).createIssuance(any(), any(), anyString(), anyString());
    verify(gateway, times(1)).sendBill(any(), any());
  }

  @Test
  @DisplayName("Nota durante baja incierta")
  void whenOriginalCancellationUnknownThenRejectNote() {
    // Consultar el comprobante aceptado no resuelve por sí solo una baja pendiente.
    var original = issue("invoices/cash/request.json", "note-during-void");
    original.setCancellation(CancellationState.builder().status("UNKNOWN").build());
    assertThatThrownBy(() -> notes.issue(COMPANY, original.getId(), "note-void-unknown",
            new CreditNoteDraft("01", "Anulación total", "FC01", original.getIssueDate(), null)))
            .isInstanceOf(ResponseStatusException.class);
    verify(gateway, times(1)).sendBill(any(), any());
  }
}
