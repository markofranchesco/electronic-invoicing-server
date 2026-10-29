package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.collection.CollectionRequest;
import pe.invoicing.app.service.collection.CollectionService;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentTotals;
import pe.invoicing.app.service.document.models.Installment;
import pe.invoicing.app.service.document.models.Payment;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionServiceTest extends AbstractServiceTest {
  private CollectionService service() { return new CollectionService(authentication, store, crypto, CLOCK); }
  private CollectionRequest payment(String amount) { return new CollectionRequest(amount, "PEN", LocalDate.now(CLOCK), "caja-1"); }
  private DocumentData credit() {
    var draft = draft("invoices/cash/request.json").toBuilder()
            .payment(Payment.builder().type("CREDIT").pendingAmount("100.00")
                    .installments(List.of(Installment.builder().amount("100.00")
                            .dueDate(LocalDate.now(CLOCK).plusDays(30)).build())).build()).build();
    return saved.get(documents.issueNewDocument(COMPANY, "credit-for-payments", draft).getDocumentId());
  }

  @Test
  @DisplayName("Inicial y abonos sucesivos hasta saldo cero")
  void whenPartialPaymentsThenPreserveOriginalFiscalDocument() {
    var document = credit();
    byte[] originalXml = files.get(document.getId() + ":XML").clone();
    var service = service();
    assertThat(service.balance(COMPANY, document.getId()).initialPayment()).isEqualTo("18.00");
    service.collect(COMPANY, document.getId(), "payment-one", payment("40.00"));
    assertThat(service.balance(COMPANY, document.getId()).outstandingAmount()).isEqualTo("60.00");
    service.collect(COMPANY, document.getId(), "payment-two", payment("60.00"));
    assertThat(service.balance(COMPANY, document.getId()).outstandingAmount()).isEqualTo("0.00");
    assertThat(saved.get(document.getId()).getPayment().getPendingAmount()).isEqualTo("100.00");
    assertThat(files.get(document.getId() + ":XML")).containsExactly(originalXml);
  }

  @Test
  @DisplayName("Repetir abono no cobra dos veces")
  void whenSamePaymentKeyThenReturnSameReceipt() {
    var document = credit();
    var first = service().collect(COMPANY, document.getId(), "same-payment", payment("40.00"));
    clearInvocations(store);
    var repeated = service().collect(COMPANY, document.getId(), "same-payment", payment("40"));
    assertThat(repeated.id()).isEqualTo(first.id());
    assertThat(service().balance(COMPANY, document.getId()).collectedAmount()).isEqualTo("40.00");
    verify(store, never()).saveDocument(any(), any());
  }

  @Test
  @DisplayName("No reutilizar clave para otro importe")
  void whenPaymentKeyChangesBodyThenConflict() {
    var document = credit();
    service().collect(COMPANY, document.getId(), "payment-conflict", payment("40.00"));
    assertThatThrownBy(() -> service().collect(COMPANY, document.getId(), "payment-conflict", payment("41.00")))
            .isInstanceOf(ResponseStatusException.class);
    assertThat(service().balance(COMPANY, document.getId()).collectedAmount()).isEqualTo("40.00");
  }

  @Test
  @DisplayName("Reversión auditable e idempotente")
  void whenPaymentReversedThenRestoreDebtWithoutRecollectingOnRetry() {
    var document = credit();
    var first = service().collect(COMPANY, document.getId(), "payment-reversed", payment("40.00"));
    var reversed = service().reverse(COMPANY, document.getId(), first.id(), "Error de caja");
    assertThat(service().reverse(COMPANY, document.getId(), first.id(), "Error de caja")).isEqualTo(reversed);
    assertThat(service().collect(COMPANY, document.getId(), "payment-reversed", payment("40.00")).reversedAt()).isNotNull();
    assertThat(service().balance(COMPANY, document.getId()).outstandingAmount()).isEqualTo("100.00");
    assertThat(service().balance(COMPANY, document.getId()).entries()).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "100.01", "1.001", "abc"})
  @DisplayName("Abono inválido o superior al saldo no se guarda")
  void whenInvalidPaymentThenDoNotSave(String amount) {
    var document = credit();
    clearInvocations(store);
    assertThatThrownBy(() -> service().collect(COMPANY, document.getId(), "invalid-payment", payment(amount)))
            .isInstanceOf(ResponseStatusException.class);
    verify(store, never()).saveDocument(any(), any());
  }

  @Test
  @DisplayName("Nota aceptada reduce deuda y permite identificar importe a devolver")
  void whenCreditNoteAfterCollectionThenExposeRefundableAmount() {
    var document = credit();
    service().collect(COMPANY, document.getId(), "payment-before-return", payment("100.00"));
    when(store.findCreditNotes(ACCOUNT, COMPANY, document.getId())).thenReturn(List.of(
            DocumentData.builder().documentType("07").status("ACCEPTED").totals(DocumentTotals.builder().totalAmount("59.00").build()).build()));
    var balance = service().balance(COMPANY, document.getId());
    assertThat(balance.outstandingAmount()).isEqualTo("0.00");
    assertThat(balance.refundableAmount()).isEqualTo("59.00");
    assertThat(balance.creditedAmount()).isEqualTo("59.00");
  }

  @Test
  @DisplayName("Nota pendiente impide cobrar un saldo incierto")
  void whenNotePendingThenRequireResolution() {
    var document = credit();
    when(store.findCreditNotes(ACCOUNT, COMPANY, document.getId())).thenReturn(List.of(DocumentData.builder().status("UNKNOWN").build()));
    assertThatThrownBy(() -> service().collect(COMPANY, document.getId(), "pending-note-payment", payment("10.00")))
            .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("Escritura concurrente obsoleta no devuelve recibo exitoso")
  void whenPersistenceDetectsConcurrentChangeThenPropagateConflict() {
    var document = credit();
    when(store.saveDocument(any(), any())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT));
    assertThatThrownBy(() -> service().collect(COMPANY, document.getId(), "concurrent-payment", payment("10.00")))
            .isInstanceOf(ResponseStatusException.class);
    assertThat(saved.get(document.getId()).getCollections()).isNull();
  }

  @Test
  @DisplayName("No cobrar en otra moneda ni con fecha futura")
  void whenCurrencyOrDateInvalidThenReject() {
    var document = credit();
    assertThatThrownBy(() -> service().collect(COMPANY, document.getId(), "wrong-currency", new CollectionRequest("10", "USD", LocalDate.now(CLOCK), null)))
            .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> service().collect(COMPANY, document.getId(), "future-payment", new CollectionRequest("10", "PEN", LocalDate.now(CLOCK).plusDays(1), null)))
            .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("Abonos sobreviven serialización del contenido existente")
  void whenContentRestoredThenKeepPaymentAndReversalHistory() {
    var document = credit();
    var entry = service().collect(COMPANY, document.getId(), "serialized-payment", payment("10"));
    service().reverse(COMPANY, document.getId(), entry.id(), "Duplicado de caja");
    var restored = JSON.readValue(JSON.writeValueAsString(saved.get(document.getId())), DocumentData.class);
    saved.put(document.getId(), restored);
    assertThat(service().balance(COMPANY, document.getId()).entries().getFirst().reversalReason()).isEqualTo("Duplicado de caja");
    assertThat(service().balance(COMPANY, document.getId()).outstandingAmount()).isEqualTo("100.00");
  }
}
