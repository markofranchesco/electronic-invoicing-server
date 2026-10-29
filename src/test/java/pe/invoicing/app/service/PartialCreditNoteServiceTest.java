package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.controller.creditnote.dtos.CreditNoteIssueRequest;
import pe.invoicing.app.controller.creditnote.mappers.CreditNoteMapper;
import pe.invoicing.app.service.creditnote.models.CreditNoteDraft;
import pe.invoicing.app.service.creditnote.models.CreditNoteRules;
import pe.invoicing.app.service.document.models.DocumentData;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PartialCreditNoteServiceTest extends AbstractServiceTest {
  private CreditNoteDraft partial() {
    return Mappers.getMapper(CreditNoteMapper.class).toService(
            fixture("credit-notes/partial/request.json", CreditNoteIssueRequest.class));
  }

  private DocumentData original() {
    var request = draft("invoices/cash/request.json");
    return saved.get(documents.issueNewDocument(COMPANY, "four-dishes-original", request.toBuilder()
            .items(List.of(request.items().getFirst().toBuilder().quantity("4").unitPrice("11.80")
                    .discountAmount("4.72").build())).build()).getDocumentId());
  }

  @Test @DisplayName("Devolución de un plato")
  void whenOneOfFourItemsReturnedThenCreditOnlyItsDiscountedValue() {
    // Motivo 07 conserva precio, descuento proporcional e impuesto del origen.
    var original = original();
    var note = saved.get(notes.issue(COMPANY, original.getId(), "partial-one-dish", partial()).getDocumentId());
    assertThat(note.getTotals().getTotalAmount()).isEqualTo("10.62");
    assertThat(note.getTotals().getTaxableAmount()).isEqualTo("9.00");
    assertThat(note.getTotals().getIgvAmount()).isEqualTo("1.62");
    assertThat(note.getItems().getFirst().getOriginalLineId()).isEqualTo(1);
    CreditNoteRules.validateBalance(original, note, List.of());
    String xml = new String(files.get(note.getId() + ":XML"), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(xml).contains("<cbc:ResponseCode>07</cbc:ResponseCode>", ">10.62<");
  }

  @Test @DisplayName("Saldo reservado por notas")
  void whenPendingCreditsExhaustQuantityThenRejectAnotherCredit() {
    // Una respuesta incierta no libera el saldo para otra caja/local.
    var original = original();
    var note = saved.get(notes.issue(COMPANY, original.getId(), "partial-reserved", partial()).getDocumentId());
    note.setStatus("UNKNOWN");
    assertThatCode(() -> CreditNoteRules.validateBalance(original, note, List.of(note, note, note))).doesNotThrowAnyException();
    assertThatThrownBy(() -> CreditNoteRules.validateBalance(original, note, List.of(note, note, note, note)))
            .isInstanceOf(ResponseStatusException.class);
    note.setStatus("REJECTED");
    assertThatCode(() -> CreditNoteRules.validateBalance(original, note, List.of(note, note, note, note)))
            .doesNotThrowAnyException();
  }

  @Test @DisplayName("Boleta otorgada antes de nota")
  void whenReceiptGrantedButSummaryPendingThenWaitForAcceptance() {
    // El otorgamiento y la aceptación del RC son condiciones distintas.
    var original = issue("receipts/cash/request.json", "receipt-grant-original");
    var request = new CreditNoteDraft("06", "Devolución total", "BC01", original.getIssueDate(), null);
    assertThatThrownBy(() -> notes.issue(COMPANY, original.getId(), "receipt-not-granted", request))
            .isInstanceOf(ResponseStatusException.class);
    documents.markGranted(COMPANY, original.getId());
    assertThatThrownBy(() -> notes.issue(COMPANY, original.getId(), "receipt-still-pending", request))
            .isInstanceOf(ResponseStatusException.class);
    original.setStatus("ACCEPTED");
    assertThat(notes.issue(COMPANY, original.getId(), "receipt-now-granted", request).getDocumentId()).isNotNull();
  }

  @Test @DisplayName("No devolver cantidades inexistentes")
  void whenPartialQuantityTooLargeOrRepeatedThenReject() {
    // El número de línea es estable aun con descripciones/productos repetidos.
    var original = original();
    for (var selection : List.of(List.of(new CreditNoteDraft.ReturnItem(1, "5")),
            List.of(new CreditNoteDraft.ReturnItem(1, "1"), new CreditNoteDraft.ReturnItem(1, "1")),
            List.of(new CreditNoteDraft.ReturnItem(2, "1")))) {
      var request = new CreditNoteDraft("07", "Devolución", "FC01", original.getIssueDate(), null, selection);
      assertThatThrownBy(() -> notes.issue(COMPANY, original.getId(), "invalid-return", request))
              .isInstanceOf(ResponseStatusException.class);
    }
  }
  @Test @DisplayName("Saldo comprobado bajo bloqueo")
  void whenOtherCheckoutReservedBalanceThenStoreRejectsBeforeAllocatingNumber() {
    // El adaptador usa el validador de negocio real y el mapper real al releer bajo el bloqueo del emisor.
    var original = original();
    var note = saved.get(notes.issue(COMPANY, original.getId(), "partial-lock-note", partial()).getDocumentId());
    var taxpayers = org.mockito.Mockito.mock(pe.invoicing.app.repository.identity.TaxpayerEntityRepository.class);
    var repository = org.mockito.Mockito.mock(pe.invoicing.app.repository.document.DocumentEntityRepository.class);
    var persistence = Mappers.getMapper(pe.invoicing.app.repository.document.mappers.DocumentPersistenceMapper.class);
    org.mockito.Mockito.when(taxpayers.companyId(ACCOUNT, COMPANY)).thenReturn(ACCOUNT);
    org.mockito.Mockito.when(taxpayers.lockCompany(ACCOUNT, COMPANY))
            .thenReturn(java.util.Optional.of(new pe.invoicing.app.repository.identity.entity.TaxpayerEntity()));
    org.mockito.Mockito.when(repository.findById(original.getId()))
            .thenReturn(java.util.Optional.of(persistence.toEntity(original, ACCOUNT)));
    var reserved = persistence.toEntity(note, ACCOUNT);
    org.mockito.Mockito.when(repository.findByTaxpayerIdAndSourceDocumentId(ACCOUNT, original.getId()))
            .thenReturn(List.of(reserved, reserved, reserved, reserved));
    var adapter = new pe.invoicing.app.repository.document.impl.DocumentStoreImpl(
            taxpayers, CLOCK, repository, series, artifacts, persistence);
    assertThatThrownBy(() -> adapter.createIssuance(ACCOUNT, note, "lock-key", "lock-body"))
            .isInstanceOf(ResponseStatusException.class);
    var order = org.mockito.Mockito.inOrder(taxpayers, repository);
    order.verify(taxpayers).lockCompany(ACCOUNT, COMPANY);
    order.verify(repository).findByTaxpayerIdAndSourceDocumentId(ACCOUNT, original.getId());
    org.mockito.Mockito.verify(series, org.mockito.Mockito.never()).allocateNextNumber(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

}
