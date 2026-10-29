package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.document.models.Customer;
import pe.invoicing.app.service.document.models.DocumentDraft;
import pe.invoicing.app.service.document.models.Payment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DocumentValidationTest extends AbstractServiceTest {
  @ParameterizedTest(name = "Operación {0}")
  @ValueSource(
          strings = {
                  "0112", "0113", "0200", "0201", "0202", "0203", "0204", "0205", "0206", "0207", "0208",
                  "0301", "0302", "0401", "1001", "1002", "1003", "1004", "2001", "2100", "2101", "2102",
                  "2103", "2104", "2105", "2106"
          })
  @DisplayName("Operación no implementada")
  void whenOperationUnsupportedThenRejectBeforeNumbering(String operation) {
    // Estos casos documentan la brecha fiscal y evitan emisiones incompletas.
    assertThatThrownBy(
            () ->
                    documents.issueNewDocument(
                            COMPANY,
                            "unsupported-operation",
                            draft("invoices/cash/request.json").toBuilder()
                                    .operationType(operation)
                                    .build()))
            .isInstanceOf(ResponseStatusException.class);
    verify(store, never()).createIssuance(any(), any(), anyString(), anyString());
    verify(gateway, never()).sendBill(any(), any());
  }

  @ParameterizedTest(name = "Código {0}")
  @ValueSource(strings = {"02", "04", "08", "09"})
  @DisplayName("Tipo no emitible")
  void whenDocumentTypeUnsupportedThenRejectBeforeNumbering(String type) {
    // El puerto de servicio no debe crear documentos fuera de su alcance.
    assertThatThrownBy(
            () ->
                    documents.issueNewDocument(
                            COMPANY,
                            "unsupported-type",
                            draft("invoices/cash/request.json").toBuilder().documentType(type).build()))
            .isInstanceOf(ResponseStatusException.class);
    verify(store, never()).createIssuance(any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("Venta sin líneas")
  void whenDocumentHasNoItemsThenRejectBeforeNumbering() {
    // Una venta vacía no debe reservar correlativo.
    assertThatThrownBy(
            () ->
                    documents.issueNewDocument(
                            COMPANY,
                            "empty-lines",
                            draft("invoices/cash/request.json").toBuilder().items(List.of()).build()))
            .isInstanceOf(ResponseStatusException.class);
    verify(store, never()).createIssuance(any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("Contado sin cuotas")
  void whenCashPaymentContainsPendingBalanceThenReject() {
    // La forma contado no puede esconder un saldo de crédito.
    assertThatThrownBy(
            () ->
                    documents.issueNewDocument(
                            COMPANY,
                            "cash-with-debt",
                            draft("invoices/cash/request.json").toBuilder()
                                    .payment(
                                            Payment.builder()
                                                    .type("CASH")
                                                    .pendingAmount("118.00")
                                                    .installments(List.of())
                                                    .build())
                                    .build()))
            .isInstanceOf(ResponseStatusException.class);
  }

  @ParameterizedTest(name = "Importe {0}, identificación {1}")
  @CsvSource({"699.99,false", "700.00,false", "700.01,true"})
  @DisplayName("Identificación en boleta")
  void whenAnonymousReceiptExceedsThresholdThenRequireCustomer(String price, boolean rejected) {
    // Se comprueban ambos lados del límite existente de identificación.
    DocumentDraft base = draft("receipts/cash/request.json");
    DocumentDraft request =
            base.toBuilder()
                    .customer(
                            Customer.builder()
                                    .documentType("0")
                                    .documentNumber("0")
                                    .name("CONSUMIDOR FINAL")
                                    .build())
                    .items(
                            List.of(base.items().getFirst().toBuilder().quantity("1").unitPrice(price).build()))
                    .build();
    if (rejected)
      assertThatThrownBy(() -> documents.issueNewDocument(COMPANY, "anonymous-limit", request))
              .isInstanceOf(ResponseStatusException.class);
    else
      assertThat(documents.issueNewDocument(COMPANY, "anonymous-limit", request).getDocumentId())
              .isNotNull();
  }

  @Test
  @DisplayName("Moneda sin conversión")
  void whenForeignReceiptHasNoExchangeRateThenReject() {
    // El resumen diario requiere la conversión prevista por este flujo.
    assertThatThrownBy(
            () ->
                    documents.issueNewDocument(
                            COMPANY,
                            "foreign-receipt",
                            draft("receipts/cash/request.json").toBuilder()
                                    .currency("USD")
                                    .exchangeRate(null)
                                    .build()))
            .isInstanceOf(ResponseStatusException.class);
  }
}
