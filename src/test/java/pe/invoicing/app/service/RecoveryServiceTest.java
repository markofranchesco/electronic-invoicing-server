package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.invoicing.app.service.cancellation.models.CancellationDetails;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.SunatSendResult;
import pe.invoicing.app.service.document.models.SunatStatus;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("Recuperar factura sin duplicar")
  void whenUnknownInvoiceHasCdrThenRecoverWithoutSending() {
    // Una constancia recuperada resuelve la emisión usando el mismo documento.
    DocumentData document = issue("invoices/cash/request.json", "recover-cdr");
    document.setStatus("UNKNOWN");
    doReturn(new SunatStatus("0", Optional.of(fixture("sunat/accepted/response.json", SunatSendResult.class))))
            .when(gateway).consultBill(document);
    assertThat(documents.recoverDocument(COMPANY, document.getId(), null).getStatus()).isEqualTo("ACCEPTED");
    verify(gateway, times(1)).sendBill(any(), any());
  }

  @Test
  @DisplayName("Reenvío del XML original")
  void whenInvoiceUnconfirmedThenRetryIdenticalSignedBytes() {
    // El reintento conserva firma, número y fecha; no crea otra emisión.
    DocumentData document = issue("invoices/cash/request.json", "recover-invoice");
    byte[] original = files.get(document.getId() + ":XML").clone();
    document.setStatus("UNKNOWN");
    document.getCommunication().setLastAttemptAt(OffsetDateTime.now(CLOCK).minusMinutes(3));
    assertThat(documents.recoverDocument(COMPANY, document.getId(), null).getStatus()).isEqualTo("ACCEPTED");
    verify(gateway, times(2)).sendBill(eq(document), aryEq(original));
    verify(store, times(1)).createIssuance(any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("Resumen sin ticket")
  void whenSummaryTicketLostThenRetrySameReferenceAndBytes() {
    // No se inventa un RC nuevo cuando SUNAT pudo haber recibido el anterior.
    DocumentData document = issue("receipts/cash/request.json", "recover-summary");
    byte[] original = files.get(document.getId() + ":SUMMARY_XML").clone();
    String reference = document.getCommunication().getBatchReference();
    document.setStatus("UNKNOWN");
    document.getCommunication().setTicket(null);
    document.getCommunication().setLastAttemptAt(OffsetDateTime.now(CLOCK).minusMinutes(3));
    documents.recoverDocument(COMPANY, document.getId(), null);
    verify(gateway, times(2)).sendSummary(eq(COMPANY), eq(reference), aryEq(original));
    assertThat(document.getCommunication().getTicket()).isEqualTo("ticket-1");
  }

  @Test
  @DisplayName("Recuperación en curso")
  void whenRecoveryAlreadyClaimedThenDoNotSendAgain() {
    // La exclusión de envíos se decide atómicamente en el puerto de documentos.
    DocumentData document = issue("invoices/cash/request.json", "recover-busy");
    document.setStatus("UNKNOWN");
    when(store.claimRecovery(ACCOUNT, COMPANY, document.getId(), false)).thenReturn(Optional.empty());
    documents.recoverDocument(COMPANY, document.getId(), null);
    verify(gateway, times(1)).sendBill(any(), any());
  }

  @Test
  @DisplayName("Preparación huérfana")
  void whenPreparationInterruptedWithoutXmlThenAllowSameKeyRetry() {
    // Sin XML guardado no hubo envío; se habilita la preparación del mismo correlativo.
    DocumentData document = issue("invoices/cash/request.json", "orphan-preparation");
    document.setStatus("PREPARING");
    document.getCommunication().setLastAttemptAt(OffsetDateTime.now(CLOCK).minusMinutes(3));
    files.remove(document.getId() + ":XML");
    assertThat(documents.recoverDocument(COMPANY, document.getId(), null).getStatus())
            .isEqualTo("PREPARATION_FAILED");
    verify(gateway, times(1)).sendBill(any(), any());
  }

  @Test
  @DisplayName("Recuperar baja incierta")
  void whenVoidTicketLostThenRetrySameCancellationXml() {
    // El reintento no crea una segunda comunicación de baja.
    DocumentData document = issue("invoices/cash/request.json", "recover-void");
    cancellations.requestCancellation(COMPANY, document.getId(),
            fixture("cancellations/invoice/request.json", CancellationDetails.class));
    var cancellation = document.getCancellation();
    byte[] original = files.get(document.getId() + ":CANCELLATION_XML").clone();
    cancellation.setStatus("UNKNOWN");
    cancellation.setTicket(null);
    cancellations.recoverCancellation(COMPANY, document.getId(), null);
    verify(gateway, times(2)).sendSummary(eq(COMPANY), eq(cancellation.getReference()), aryEq(original));
    assertThat(cancellation.getStatus()).isEqualTo("SUBMITTED");
  }

  @Test
  @DisplayName("Conservar fecha de primer envío")
  void whenRecoveryRetriesThenDoNotMoveAcceptanceBaseline() {
    // El reintento puede cambiar lastAttemptAt, pero nunca la evidencia temporal del primer envío.
    DocumentData document = issue("invoices/cash/request.json", "recovery-date");
    var sent = OffsetDateTime.now(CLOCK).minusDays(1);
    document.getCommunication().setFirstAttemptAt(sent);
    document.getTaxResponse().setAcceptedAt(null);
    document.setStatus("UNKNOWN");
    documents.recoverDocument(COMPANY, document.getId(), null);
    assertThat(document.getTaxResponse().getAcceptedAt()).isNull();
    assertThat(document.getTaxResponse().getAcceptanceDeadlineBase()).isEqualTo(sent);
  }

  @Test
  @DisplayName("Credenciales rechazadas")
  void whenSoapCredentialsRejectedThenExposeRetryableSendFailureWithoutCdr() {
    // Un fallo de autenticación se corrige y reintenta; no fabrica un CDR de rechazo.
    doThrow(new pe.invoicing.app.service.document.models.SunatFaultException("0102", "Credenciales inválidas"))
            .when(gateway).sendBill(any(), any());
    assertThatThrownBy(() -> issue("invoices/cash/request.json", "soap-credentials"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(saved.values()).singleElement().satisfies(document -> {
      assertThat(document.getStatus()).isEqualTo("SEND_FAILED");
      assertThat(document.getTaxResponse().getCode()).isEqualTo("0102");
      assertThat(document.getCdrAvailable()).isFalse();
    });
  }

  @Test
  @DisplayName("Consulta en entorno distinto")
  void whenUnknownDocumentBelongsToAnotherEnvironmentThenDoNotConsult() {
    // La consulta tampoco debe enviar un documento de beta a producción o viceversa.
    DocumentData document = issue("invoices/cash/request.json", "different-environment");
    document.setStatus("UNKNOWN");
    document.setEnvironment("production");
    assertThatThrownBy(() -> documents.getDocument(COMPANY, document.getId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    verify(gateway, never()).consultBill(any());
  }

  @Test
  @DisplayName("Baja en entorno distinto")
  void whenUnknownCancellationBelongsToAnotherEnvironmentThenDoNotResend() {
    // El XML de baja conserva el entorno de la emisión original.
    DocumentData document = issue("invoices/cash/request.json", "void-environment");
    cancellations.requestCancellation(COMPANY, document.getId(),
            fixture("cancellations/invoice/request.json", CancellationDetails.class));
    document.getCancellation().setStatus("UNKNOWN");
    document.getCancellation().setTicket(null);
    document.setEnvironment("production");
    assertThatThrownBy(() -> cancellations.recoverCancellation(COMPANY, document.getId(), null))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    verify(gateway, times(1)).sendSummary(anyString(), anyString(), any());
  }
}
