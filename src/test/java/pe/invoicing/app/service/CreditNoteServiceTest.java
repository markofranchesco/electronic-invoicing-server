package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.controller.creditnote.dtos.CreditNoteIssueRequest;
import pe.invoicing.app.controller.creditnote.mappers.CreditNoteMapper;
import pe.invoicing.app.service.creditnote.models.CreditNoteDraft;
import pe.invoicing.app.service.document.models.DocumentData;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CreditNoteServiceTest extends AbstractServiceTest {
  @ParameterizedTest(name = "Motivo {0}")
  @ValueSource(strings = {"cancellation", "total-return"})
  @DisplayName("Nota total")
  void whenTotalNoteThenPreserveOriginalAmounts(String reason) {
    // El servicio usa el comprobante original y el mapper real.
    DocumentData original = issue("invoices/cash/request.json", "note-original");
    DocumentData note =
            saved.get(
                    notes
                            .issue(
                                    COMPANY,
                                    original.getId(),
                                    "note-total",
                                    Mappers.getMapper(CreditNoteMapper.class)
                                            .toService(
                                                    fixture(
                                                            "credit-notes/" + reason + "/request.json",
                                                            CreditNoteIssueRequest.class)))
                            .getDocumentId());
    assertThat(note.getTotals()).isEqualTo(original.getTotals());
    assertThat(note.getSourceDocumentId()).isEqualTo(original.getId());
    assertThat(note.getDocumentType()).isEqualTo("07");
    assertThat(note.getStatus()).isEqualTo("ACCEPTED");
  }

  @Test
  @DisplayName("Nota anterior al original")
  void whenNotePredatesOriginalThenReject() {
    // La fecha de la nota no puede preceder a su comprobante.
    DocumentData original = issue("invoices/cash/request.json", "note-date-original");
    assertThatThrownBy(
            () ->
                    notes.issue(
                            COMPANY,
                            original.getId(),
                            "note-date",
                            new CreditNoteDraft(
                                    "06", "Devolución total", "FC01", LocalDate.of(2026, 10, 25), null)))
            .isInstanceOf(ResponseStatusException.class);
  }

  @ParameterizedTest(name = "Motivo pendiente {0}")
  @ValueSource(strings = {"08", "10", "11", "12"})
  @DisplayName("Motivo aún no soportado")
  void whenUnsupportedReasonThenRejectWithoutSending(String reason) {
    // Esta prueba acredita rechazo seguro, no implementación del motivo.
    assertThatThrownBy(
            () ->
                    notes.issue(
                            COMPANY,
                            UUID.randomUUID(),
                            "unsupported",
                            new CreditNoteDraft(
                                    reason, "Motivo de prueba", "FC01", LocalDate.of(2026, 10, 26), null)))
            .isInstanceOf(ResponseStatusException.class);
    verify(gateway, never()).sendBill(any(), any());
  }
}
