package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.SunatSendResult;
import pe.invoicing.app.service.document.models.SunatStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SummaryServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("Rectificar resumen aceptado")
  void whenAcceptedSummaryIsReportedAgainThenUseConditionTwoAndKeepSignedBill() {
    // Rectificar el reporte no modifica el comprobante firmado ni consume otra numeración de venta.
    DocumentData document = acceptedReceipt();
    String previous = document.getCommunication().getBatchReference();
    byte[] signedBill = files.get(document.getId() + ":XML").clone();
    documents.reportSummary(COMPANY, document.getId(), previous);
    assertThat(new String(files.get(document.getId() + ":SUMMARY_XML"), java.nio.charset.StandardCharsets.UTF_8))
            .contains("<cbc:ConditionCode>2</cbc:ConditionCode>");
    assertThat(files.get(document.getId() + ":XML")).isEqualTo(signedBill);
    assertThat(files.get(document.getId() + ":SUMMARY_XML:" + previous)).isNotEmpty();
    verify(store, times(1)).createIssuance(any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("Rectificación rechazada")
  void whenCorrectionRejectedThenPreservePreviouslyAcceptedReceipt() {
    // Rechazar una rectificación del RC no invalida la aceptación anterior de la boleta.
    DocumentData document = acceptedReceipt();
    documents.reportSummary(COMPANY, document.getId(), document.getCommunication().getBatchReference());
    doReturn(new SunatStatus("0", Optional.of(fixture("sunat/rejected/response.json", SunatSendResult.class))))
            .when(gateway).getSummaryStatus(anyString(), anyString(), anyString());
    assertThat(documents.getDocument(COMPANY, document.getId()).getStatus()).isEqualTo("ACCEPTED");
    assertThat(document.getCommunication().getStatus()).isEqualTo("REJECTED");
    assertThat(document.getTaxResponse().getCode()).isEqualTo("0");
  }

  @Test
  @DisplayName("Reintentar resumen rechazado")
  void whenInitialSummaryRejectedThenReportWithConditionOne() {
    // Se informa de nuevo el comprobante original dentro del plazo, conservando el RC rechazado.
    DocumentData document = issue("receipts/cash/request.json", "summary-rejected");
    doReturn(new SunatStatus("0", Optional.of(fixture("sunat/rejected/response.json", SunatSendResult.class))))
            .when(gateway).getSummaryStatus(anyString(), anyString(), anyString());
    documents.getDocument(COMPANY, document.getId());
    documents.reportSummary(COMPANY, document.getId(), document.getCommunication().getBatchReference());
    assertThat(new String(files.get(document.getId() + ":SUMMARY_XML"), java.nio.charset.StandardCharsets.UTF_8))
            .contains("<cbc:ConditionCode>1</cbc:ConditionCode>");
  }

  @Test
  @DisplayName("Rectificación idempotente")
  void whenSamePreviousReferenceRepeatedThenDoNotCreateAnotherSummary() {
    // La referencia anterior identifica de forma estable una solicitud de rectificación.
    DocumentData document = acceptedReceipt();
    String previous = document.getCommunication().getBatchReference();
    documents.reportSummary(COMPANY, document.getId(), previous);
    documents.reportSummary(COMPANY, document.getId(), previous);
    verify(gateway, times(2)).sendSummary(anyString(), anyString(), any());
  }

  private DocumentData acceptedReceipt() {
    DocumentData document = issue("receipts/cash/request.json", "summary-original");
    doReturn(new SunatStatus("0", Optional.of(fixture("sunat/accepted/response.json", SunatSendResult.class))))
            .when(gateway).getSummaryStatus(anyString(), anyString(), anyString());
    return documents.getDocument(COMPANY, document.getId());
  }

  @Test
  @DisplayName("Rectificación interrumpida")
  void whenCorrectionPreparationFailsThenRecoverItsOwnSummary() {
    // Un RC nuevo nunca puede reenviar los bytes de la referencia anterior.
    DocumentData document = acceptedReceipt();
    String previous = document.getCommunication().getBatchReference();
    doThrow(new IllegalStateException("Interrupción local")).when(gateway)
            .signedSummary(any(), any(), anyString(), any(), eq("2"));
    assertThatThrownBy(() -> documents.reportSummary(COMPANY, document.getId(), previous))
            .isInstanceOf(IllegalStateException.class);
    doCallRealMethod().when(gateway).signedSummary(any(), any(), anyString(), any(), eq("2"));
    documents.recoverDocument(COMPANY, document.getId(), null);
    assertThat(new String(files.get(document.getId() + ":SUMMARY_XML"), java.nio.charset.StandardCharsets.UTF_8))
            .contains("RC-20261026-2", "<cbc:ConditionCode>2</cbc:ConditionCode>");
    verify(gateway).sendSummary(eq(COMPANY), eq("RC-20261026-2"), any());
  }
}
