package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import pe.invoicing.app.controller.document.DocumentController;
import pe.invoicing.app.controller.document.dtos.requests.DocumentUpsertRequest;
import pe.invoicing.app.controller.document.mappers.DocumentResponseMapper;
import pe.invoicing.app.controller.creditnote.CreditNoteController;
import pe.invoicing.app.controller.creditnote.dtos.CreditNoteIssueRequest;
import pe.invoicing.app.controller.creditnote.mappers.CreditNoteMapper;
import pe.invoicing.app.controller.cancellation.CancellationController;
import pe.invoicing.app.controller.cancellation.dtos.CancellationRequest;
import pe.invoicing.app.controller.cancellation.mappers.CancellationMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AcceptanceControllerTest extends AbstractServiceTest {
  @Test
  @DisplayName("Controlador de venta responde con ID persistido antes del envío")
  void whenIssueControllerThenPersistPendingWithoutSoap() {
    var controller = new DocumentController(mapper, Mappers.getMapper(DocumentResponseMapper.class), documents);
    var response = controller.issue(COMPANY, "controller-durable-sale", fixture("invoices/cash/request.json", DocumentUpsertRequest.class));
    assertThat(response.status()).isEqualTo("PENDING");
    assertThat(saved.get(response.documentId()).getStatus()).isEqualTo("DRAFT");
    verify(gateway, never()).sendBill(any(), any());
  }

  @Test
  @DisplayName("Controlador de nota deja trabajo durable")
  void whenNoteControllerThenPersistPendingWithoutSoap() {
    var original = issue("invoices/cash/request.json", "controller-note-original");
    clearInvocations(gateway);
    var controller = new CreditNoteController(notes, Mappers.getMapper(CreditNoteMapper.class));
    var response = controller.issue(COMPANY, original.getId(), "controller-durable-note",
            fixture("credit-notes/total-return/request.json", CreditNoteIssueRequest.class));
    assertThat(response.status()).isEqualTo("PENDING");
    assertThat(saved.get(response.documentId()).getSourceDocumentId()).isEqualTo(original.getId());
    assertThat(saved.get(response.documentId()).getStatus()).isEqualTo("DRAFT");
    verify(gateway, never()).sendBill(any(), any());
  }

  @Test
  @DisplayName("Controlador de baja conserva pendiente antes del envío")
  void whenCancellationControllerThenPersistPendingWithoutSoap() {
    var original = issue("invoices/cash/request.json", "controller-void-original");
    var controller = new CancellationController(cancellations, Mappers.getMapper(CancellationMapper.class));
    var response = controller.cancel(COMPANY, original.getId(), fixture("cancellations/invoice/request.json", CancellationRequest.class));
    assertThat(response.status()).isEqualTo("PENDING");
    assertThat(original.getCancellation().getStatus()).isEqualTo("PENDING");
    verify(gateway, never()).sendSummary(anyString(), anyString(), any());
  }
}
