package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.document.models.DocumentDraft;
import pe.invoicing.app.service.document.models.Installment;
import pe.invoicing.app.service.document.models.Payment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CriticalValidationServiceTest extends AbstractServiceTest {
  @ParameterizedTest(name = "Campo {0}")
  @ValueSource(strings = {"body", "type", "series", "date", "currency", "customer",
          "customerType", "customerNumber", "customerName", "unit", "description", "quantity",
          "price", "discount", "rate", "paymentType", "note"})
  @DisplayName("Validar antes de numerar")
  void whenServiceInputInvalidThenRejectWithoutNumberingOrSending(String field) {
    // La entrada directa al servicio tiene las mismas garantías que la entrada HTTP.
    DocumentDraft base = draft("invoices/cash/request.json");
    DocumentDraft request = switch (field) {
      case "body" -> null;
      case "type" -> base.toBuilder().documentType(null).build();
      case "series" -> base.toBuilder().series("F").build();
      case "date" -> base.toBuilder().issueDate(null).build();
      case "currency" -> base.toBuilder().currency(null).build();
      case "customer" -> base.toBuilder().customer(null).build();
      case "customerType" -> base.toBuilder().customer(base.customer().toBuilder().documentType(null).build()).build();
      case "customerNumber" ->
              base.toBuilder().customer(base.customer().toBuilder().documentNumber("123").build()).build();
      case "customerName" -> base.toBuilder().customer(base.customer().toBuilder().name(" ").build()).build();
      case "unit" ->
              base.toBuilder().items(List.of(base.items().getFirst().toBuilder().unitCode("XYZ").build())).build();
      case "description" ->
              base.toBuilder().items(List.of(base.items().getFirst().toBuilder().description(" ").build())).build();
      case "quantity" ->
              base.toBuilder().items(List.of(base.items().getFirst().toBuilder().quantity(null).build())).build();
      case "price" ->
              base.toBuilder().items(List.of(base.items().getFirst().toBuilder().unitPrice("abc").build())).build();
      case "discount" ->
              base.toBuilder().items(List.of(base.items().getFirst().toBuilder().discountAmount("0.001").build())).build();
      case "rate" -> base.toBuilder().exchangeRate("1e2").build();
      case "paymentType" -> base.toBuilder().payment(base.payment().toBuilder().type(null).build()).build();
      default -> base.toBuilder().documentType("07").note(null).build();
    };
    assertThatThrownBy(() -> documents.issueNewDocument(COMPANY, "invalid-service-input", request))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode().value()).isEqualTo(422));
    verify(store, never()).createIssuance(any(), any(), anyString(), anyString());
    verify(gateway, never()).signedXml(any(), any());
    verify(gateway, never()).sendBill(any(), any());
  }

  @ParameterizedTest(name = "Crédito {0}")
  @ValueSource(strings = {"balance", "precision", "installments", "amount", "date"})
  @DisplayName("Crédito incompleto")
  void whenCreditMalformedThenRejectAtServiceBoundary(String field) {
    // Un saldo o cronograma incompleto produce un error de negocio, no una excepción interna.
    DocumentDraft base = draft("invoices/cash/request.json");
    Installment installment = Installment.builder().dueDate(base.issueDate().plusDays(30)).amount("10.00").build();
    Payment.PaymentBuilder payment = Payment.builder().type("CREDIT").pendingAmount("10.00").installments(List.of(installment));
    switch (field) {
      case "balance" -> payment.pendingAmount(null);
      case "precision" -> payment.pendingAmount("10.001");
      case "installments" -> payment.installments(null);
      case "amount" -> payment.installments(List.of(installment.toBuilder().amount(null).build()));
      default -> payment.installments(List.of(installment.toBuilder().dueDate(null).build()));
    }
    assertThatThrownBy(() -> documents.issueNewDocument(COMPANY, "invalid-credit-data",
            base.toBuilder().payment(payment.build()).build()))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode().value()).isEqualTo(422));
    verify(store, never()).createIssuance(any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("Saldo parcial firmado")
  void whenCreditHasInitialPaymentThenXmlContainsOnlyOutstandingInstallments() {
    // El total de la venta es 118, pero únicamente 100 quedan pendientes de pago.
    DocumentDraft base = draft("invoices/cash/request.json");
    Payment payment = Payment.builder().type("CREDIT").pendingAmount("100.00")
            .installments(List.of(Installment.builder().dueDate(base.issueDate().plusDays(30)).amount("40.00").build(),
                    Installment.builder().dueDate(base.issueDate().plusDays(60)).amount("60.00").build())).build();
    var result = documents.issueNewDocument(COMPANY, "partial-credit-xml", base.toBuilder()
            .items(List.of(base.items().getFirst().toBuilder().quantity("1").unitPrice("118.00").build()))
            .payment(payment).build());
    String xml = new String(files.get(result.getDocumentId() + ":XML"), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(xml).contains("Credito", "Cuota001", "Cuota002", ">100.00<", ">40.00<", ">60.00<", ">118.00<");
    assertThat(saved.get(result.getDocumentId()).getTotals().getTotalAmount()).isEqualTo("118.00");
  }
}
