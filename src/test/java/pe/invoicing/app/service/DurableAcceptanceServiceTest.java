package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.invoicing.app.service.document.models.IssueOperation;
import pe.invoicing.app.service.document.models.ReconciliationState;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableAcceptanceServiceTest extends AbstractServiceTest {
  @ParameterizedTest
  @ValueSource(strings = {"invoices/cash/request.json", "receipts/cash/request.json"})
  @DisplayName("Aceptar venta sin esperar a SUNAT y recuperarla tras reinicio")
  void whenAcceptedThenPersistBeforeRemoteSend(String fixture) {
    var request = draft(fixture);
    var result = documents.acceptNewDocument(COMPANY, "durable-acceptance", request);
    var document = saved.get(result.getDocumentId());
    var number = document.getNumber();
    assertThat(document.getStatus()).isEqualTo("DRAFT");
    assertThat(ReconciliationState.pending(document)).isTrue();
    verify(gateway, never()).sendBill(any(), any());
    verify(gateway, never()).sendSummary(anyString(), anyString(), any());
    verify(gateway, never()).signedXml(any(), any());
    // Reconstruir el contenido simula la lectura persistente sin conservar la instancia del request.
    saved.put(document.getId(), JSON.readValue(JSON.writeValueAsString(document), pe.invoicing.app.service.document.models.DocumentData.class));
    var recovered = documents.recoverDocument(COMPANY, document.getId(), null);
    assertThat(recovered.getNumber()).isEqualTo(number);
    assertThat(recovered.getStatus()).isIn("ACCEPTED", "SUBMITTED");
    verify(store, times(1)).createIssuance(any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("Respuesta 202 perdida no crea otra venta")
  void whenAcceptanceRepeatedThenKeepReservedIdentity() {
    var request = draft("invoices/cash/request.json");
    var first = documents.acceptNewDocument(COMPANY, "lost-202-reply", request);
    when(artifacts.findIssueOperation(ACCOUNT, COMPANY, crypto.sha256("lost-202-reply")))
            .thenReturn(Optional.of(new IssueOperation(crypto.sha256(JSON.valueToTree(request).toString()), first.getDocumentId())));
    assertThat(documents.acceptNewDocument(COMPANY, "lost-202-reply", request).getDocumentId()).isEqualTo(first.getDocumentId());
    verify(store, times(1)).createIssuance(any(), any(), anyString(), anyString());
    verify(gateway, never()).sendBill(any(), any());
  }

  @Test
  @DisplayName("Solicitud inválida nunca se encola")
  void whenInvalidCurrencyThenRejectBeforeReservation() {
    assertThatThrownBy(() -> documents.acceptNewDocument(COMPANY, "invalid-durable-request",
            draft("invoices/cash/request.json").toBuilder().currency("GBP").build()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    verify(store, never()).createIssuance(any(), any(), anyString(), anyString());
  }
  @Test
  @DisplayName("Cambio posterior de empresa no altera el emisor reservado")
  void whenCompanyChangesBeforeWorkerThenPreserveIssuerSnapshot() {
    var accepted = documents.acceptNewDocument(COMPANY, "issuer-snapshot-original", draft("invoices/cash/request.json"));
    var document = saved.get(accepted.getDocumentId());
    var originalName = document.getIssuerLegalName();
    var originalStreet = document.getIssuerFiscalAddress();
    when(companyStore.getCompany(ACCOUNT, COMPANY)).thenReturn(
            pe.invoicing.app.service.company.models.Company.builder().id(java.util.UUID.randomUUID())
                    .accountId(ACCOUNT).documentNumber(COMPANY).legalName("NOMBRE MODIFICADO DESPUES")
                    .address(companyData.address().toBuilder().street("DOMICILIO MODIFICADO DESPUES").build()).build());
    documents.recoverDocument(COMPANY, document.getId(), null);
    String xml = new String(files.get(document.getId() + ":XML"), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(xml).contains(originalName, originalStreet)
            .doesNotContain("NOMBRE MODIFICADO DESPUES", "DOMICILIO MODIFICADO DESPUES");
  }

}
