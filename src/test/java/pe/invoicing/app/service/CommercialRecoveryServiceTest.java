package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.IssueOperation;
import pe.invoicing.app.service.document.models.ReconciliationState;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommercialRecoveryServiceTest extends AbstractServiceTest {
  private DocumentData reservedBeforeCrash(String fixture) {
    // La caída ocurre después de persistir la venta, antes de reservar su preparación.
    doThrow(new IllegalStateException("Proceso interrumpido antes de firmar"))
            .when(store).claimEmission(eq(ACCOUNT), eq(COMPANY), any());
    assertThatThrownBy(() -> issue(fixture, "reserved-before-crash"))
            .isInstanceOf(IllegalStateException.class);
    DocumentData document = saved.values().iterator().next();
    assertThat(document.getStatus()).isEqualTo("DRAFT");
    // El puerto simulado conserva la operación y la referencia al reiniciar.
    org.mockito.Mockito.doAnswer(call -> {
      document.setStatus("PREPARING");
      document.getCommunication().setLastAttemptAt(OffsetDateTime.now(CLOCK));
      document.getCommunication().setFirstAttemptAt(OffsetDateTime.now(CLOCK));
      if ("DAILY_SUMMARY".equals(document.getCommunication().getMethod()))
        document.getCommunication().setBatchReference("RC-20261026-1");
      return Optional.of(document);
    }).when(store).claimEmission(eq(ACCOUNT), eq(COMPANY), eq(document.getId()));
    return document;
  }

  @ParameterizedTest
  @CsvSource({"invoices/cash/request.json,ACCEPTED", "receipts/cash/request.json,SUBMITTED"})
  @DisplayName("Reiniciar después de reservar sin duplicar venta")
  void whenReservedBeforeCrashThenRecoverSameIdentity(String fixture, String expected) {
    DocumentData document = reservedBeforeCrash(fixture);
    var id = document.getId();
    var number = document.getNumber();
    assertThat(ReconciliationState.pending(document)).isTrue();
    var recovered = documents.recoverDocument(COMPANY, id, null);
    assertThat(recovered.getStatus()).isEqualTo(expected);
    assertThat(recovered.getId()).isEqualTo(id);
    assertThat(recovered.getNumber()).isEqualTo(number);
    assertThat(files.get(id + ":XML")).isNotEmpty();
    verify(store, times(1)).createIssuance(any(), any(), anyString(), anyString());
    verify(gateway, never()).consultBill(any());
    if ("SUBMITTED".equals(expected)) {
      verify(gateway, times(1)).sendSummary(eq(COMPANY), eq("RC-20261026-1"), any());
      verify(gateway, never()).sendBill(any(), any());
      assertThat(files.get(id + ":SUMMARY_XML")).isNotEmpty();
    } else verify(gateway, times(1)).sendBill(any(), any());
  }

  @Test
  @DisplayName("La reserva de otro proceso impide preparar dos veces")
  void whenReservedDocumentAlreadyClaimedThenDoNotSend() {
    var document = reservedBeforeCrash("invoices/cash/request.json");
    when(store.claimEmission(ACCOUNT, COMPANY, document.getId())).thenReturn(Optional.empty());
    assertThat(documents.recoverDocument(COMPANY, document.getId(), null).getId()).isEqualTo(document.getId());
    verify(gateway, never()).signedXml(any(), any());
    verify(gateway, never()).sendBill(any(), any());
  }

  @Test
  @DisplayName("Una reserva vencida no se convierte en emisión nueva")
  void whenReservedDocumentExpiredThenDoNotClaimOrSend() {
    var document = reservedBeforeCrash("invoices/cash/request.json");
    document.setIssueDate(LocalDate.now(CLOCK).minusDays(4));
    clearInvocations(store, gateway);
    assertThatThrownBy(() -> documents.recoverDocument(COMPANY, document.getId(), null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(422));
    verify(store, never()).claimEmission(any(), anyString(), any());
    verify(gateway, never()).sendBill(any(), any());
    assertThat(document.getStatus()).isEqualTo("DRAFT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNKNOWN", "PREPARATION_FAILED"})
  @DisplayName("No reenviar XML alterado ni reemplazar su huella")
  void whenSignedXmlChangedThenRequireOriginal(String state) {
    var document = issue("invoices/cash/request.json", "changed-signed-xml");
    var digest = document.getSignedXmlSha256();
    document.setStatus(state);
    document.getCommunication().setLastAttemptAt(OffsetDateTime.now(CLOCK).minusMinutes(3));
    files.put(document.getId() + ":XML", new byte[]{1, 2, 3});
    clearInvocations(gateway, store);
    assertThatThrownBy(() -> documents.recoverDocument(COMPANY, document.getId(), null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(409));
    assertThat(document.getSignedXmlSha256()).isEqualTo(digest);
    verify(gateway, never()).sendBill(any(), any());
    verify(gateway, never()).signedXml(any(), any());
    verify(store, never()).createIssuance(any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("Misma clave conserva XML firmado al repetir preparación")
  void whenPreparationRetryAlreadyHasXmlThenDoNotSignAgain() {
    var request = draft("invoices/cash/request.json");
    String key = "same-preparation-original";
    var document = issue("invoices/cash/request.json", key);
    var original = files.get(document.getId() + ":XML").clone();
    document.setStatus("PREPARATION_FAILED");
    when(artifacts.findIssueOperation(ACCOUNT, COMPANY, crypto.sha256(key)))
            .thenReturn(Optional.of(new IssueOperation(crypto.sha256(JSON.valueToTree(request).toString()), document.getId())));
    clearInvocations(gateway, store);
    assertThat(documents.issueNewDocument(COMPANY, key, request).getDocumentId()).isEqualTo(document.getId());
    assertThat(files.get(document.getId() + ":XML")).containsExactly(original);
    verify(gateway, never()).signedXml(any(), any());
    verify(gateway).sendBill(eq(document), org.mockito.AdditionalMatchers.aryEq(original));
    verify(store, never()).createIssuance(any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("Misma clave no regenera un XML original perdido")
  void whenPreparationRetryLostSignedXmlThenRequireRestore() {
    var request = draft("invoices/cash/request.json");
    String key = "lost-original-same-key";
    var document = issue("invoices/cash/request.json", key);
    var digest = document.getSignedXmlSha256();
    document.setStatus("PREPARATION_FAILED");
    files.remove(document.getId() + ":XML");
    when(artifacts.findIssueOperation(ACCOUNT, COMPANY, crypto.sha256(key)))
            .thenReturn(Optional.of(new IssueOperation(crypto.sha256(JSON.valueToTree(request).toString()), document.getId())));
    clearInvocations(gateway);
    assertThatThrownBy(() -> documents.issueNewDocument(COMPANY, key, request))
            .isInstanceOf(ResponseStatusException.class);
    assertThat(document.getSignedXmlSha256()).isEqualTo(digest);
    verify(gateway, never()).signedXml(any(), any());
    verify(gateway, never()).sendBill(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS", "REJECTED", "CANCELLED"})
  @DisplayName("Estado terminal no vuelve a emitir al solicitar recuperación")
  void whenTerminalThenRecoveryDoesNotContactSunat(String state) {
    var document = issue("invoices/cash/request.json", "terminal-no-retry");
    document.setStatus(state);
    clearInvocations(gateway, store);
    assertThat(documents.recoverDocument(COMPANY, document.getId(), null).getStatus()).isEqualTo(state);
    verify(gateway, never()).sendBill(any(), any());
    verify(gateway, never()).consultBill(any());
    verify(store, never()).claimRecovery(any(), anyString(), any(), anyBoolean());
  }
}
