package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.invoicing.app.service.cancellation.models.CancellationDetails;
import pe.invoicing.app.service.document.models.SunatSendResult;
import pe.invoicing.app.service.document.models.SunatStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CommunicationStateServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("RC pendiente de proceso")
  void whenSummaryProcessingThenRecordCodeWithoutFiscalAcceptance() {
    // Una respuesta 98 indica procesamiento, nunca aceptación del comprobante.
    var document = issue("receipts/cash/request.json", "processing-summary");
    documents.getDocument(COMPANY, document.getId());
    assertThat(document.getStatus()).isEqualTo("SUBMITTED");
    assertThat(document.getCommunication().getResponseCode()).isEqualTo("98");
    assertThat(document.getCdrAvailable()).isFalse();
    verify(store, atLeastOnce()).saveDocument(ACCOUNT, document);
  }

  @ParameterizedTest(name = "Respuesta {0}")
  @ValueSource(strings = {"0", "99", "UNKNOWN"})
  @DisplayName("RC sin constancia")
  void whenSummaryHasNoCdrThenKeepUnconfirmedFiscalState(String code) {
    // El error terminal del envío y la ausencia de constancia no prueban rechazo tributario.
    var document = issue("receipts/cash/request.json", "missing-cdr-summary");
    doReturn(new SunatStatus(code, Optional.empty())).when(gateway)
            .getSummaryStatus(anyString(), anyString(), anyString());
    documents.getDocument(COMPANY, document.getId());
    assertThat(document.getStatus()).isEqualTo("UNKNOWN");
    assertThat(document.getCommunication().getStatus())
            .isEqualTo("99".equals(code) ? "MANUAL_REVIEW" : "RESPONSE_INCOMPLETE");
    assertThat(document.getCommunication().getResponseCode()).isEqualTo(code);
    assertThat(document.getCdrAvailable()).isFalse();
  }

  @ParameterizedTest(name = "Código incompatible {0}")
  @ValueSource(strings = {"98", "99", "OTHER"})
  @DisplayName("Respuesta contradictoria")
  void whenStatusContradictsAcceptedCdrThenDoNotAccept(String code) {
    // Un contenido aceptado no se aplica si contradice el resultado de la consulta.
    var document = issue("receipts/cash/request.json", "contradictory-summary");
    doReturn(new SunatStatus(code, Optional.of(fixture("sunat/accepted/response.json", SunatSendResult.class))))
            .when(gateway).getSummaryStatus(anyString(), anyString(), anyString());
    documents.getDocument(COMPANY, document.getId());
    assertThat(document.getStatus()).isEqualTo("UNKNOWN");
    assertThat(document.getCommunication().getStatus()).isEqualTo("MANUAL_REVIEW");
    assertThat(document.getCdrAvailable()).isFalse();
  }

  @ParameterizedTest(name = "Baja sin CDR {0}")
  @ValueSource(strings = {"0", "99"})
  @DisplayName("Baja no confirmada")
  void whenCancellationHasNoCdrThenPreserveAcceptedDocument(String code) {
    // Solo una CDR de baja aceptada puede anular el comprobante original.
    var document = issue("invoices/cash/request.json", "void-missing-cdr");
    cancellations.requestCancellation(COMPANY, document.getId(), fixture("cancellations/invoice/request.json", CancellationDetails.class));
    doReturn(new SunatStatus(code, Optional.empty())).when(gateway)
            .getSummaryStatus(anyString(), anyString(), anyString());
    var cancellation = cancellations.getCancellation(COMPANY, document.getId());
    assertThat(document.getStatus()).isEqualTo("ACCEPTED");
    assertThat(cancellation.getStatus()).isEqualTo("99".equals(code) ? "MANUAL_REVIEW" : "UNKNOWN");
    assertThat(cancellation.getCdrAvailable()).isFalse();
    assertThat(cancellation.getCompletedAt()).isNull();
  }

  @Test
  @DisplayName("Recuperar baja con ticket")
  void whenUnknownCancellationHasTicketThenConsultAgain() {
    // El ticket persistido permite resolver una respuesta anterior incompleta.
    var document = issue("invoices/cash/request.json", "void-known-ticket");
    cancellations.requestCancellation(COMPANY, document.getId(), fixture("cancellations/invoice/request.json", CancellationDetails.class));
    document.getCancellation().setStatus("UNKNOWN");
    doReturn(new SunatStatus("0", Optional.of(fixture("sunat/accepted/response.json", SunatSendResult.class))))
            .when(gateway).getSummaryStatus(anyString(), anyString(), anyString());
    assertThat(cancellations.recoverCancellation(COMPANY, document.getId(), null).getStatus()).isEqualTo("ACCEPTED");
    assertThat(document.getStatus()).isEqualTo("CANCELLED");
    verify(gateway, times(1)).sendSummary(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("No volver a consultar aceptación")
  void whenReceiptAlreadyAcceptedThenKeepFiscalResultOnRepeatedRead() {
    // Una lectura repetida no reemplaza una constancia final por un resultado posterior distinto.
    var document = issue("receipts/cash/request.json", "terminal-accepted");
    doReturn(new SunatStatus("0", Optional.of(fixture("sunat/accepted/response.json", SunatSendResult.class))))
            .when(gateway).getSummaryStatus(anyString(), anyString(), anyString());
    documents.getDocument(COMPANY, document.getId());
    doReturn(new SunatStatus("99", Optional.empty())).when(gateway)
            .getSummaryStatus(anyString(), anyString(), anyString());
    assertThat(documents.getDocument(COMPANY, document.getId()).getStatus()).isEqualTo("ACCEPTED");
    verify(gateway, times(1)).getSummaryStatus(anyString(), anyString(), anyString());
  }
}
