package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.invoicing.app.service.cancellation.models.CancellationDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RecoveryCompletionServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("Completar preparación sin archivo")
  void whenPreparationFailedBeforeSigningThenRecoverSameDocument() {
    // El documento ya reservado se reconstruye sin cambiar su número ni crear otra emisión.
    var document = issue("invoices/cash/request.json", "recover-local-preparation");
    document.setStatus("PREPARATION_FAILED");
    document.setXmlAvailable(false);
    document.setSignedXmlSha256(null);
    document.setTaxResponse(null);
    files.remove(document.getId() + ":XML");
    files.remove(document.getId() + ":CDR");
    assertThat(documents.recoverDocument(COMPANY, document.getId(), null).getStatus()).isEqualTo("ACCEPTED");
    verify(store, times(1)).createIssuance(any(), any(), anyString(), anyString());
    assertThat(files.get(document.getId() + ":XML")).isNotEmpty();
  }

  @Test
  @DisplayName("Reparar baja rechazada explícitamente")
  void whenRejectedCancellationExplicitlyRetriedThenCreateNewCommunication() {
    // El intento rechazado se identifica expresamente y no se reutiliza su XML.
    var document = issue("invoices/cash/request.json", "void-rejected-retry");
    var first = cancellations.requestCancellation(COMPANY, document.getId(),
            new CancellationDetails("Documento no otorgado", "COMMUNICATION_BAJA", true));
    first.setStatus("REJECTED");
    first.setSunatCode("2001");
    var second = cancellations.requestCancellation(COMPANY, document.getId(),
            new CancellationDetails("Corrección del sustento", "COMMUNICATION_BAJA", true, first.getReference()));
    assertThat(second.getPreviousReference()).isEqualTo(first.getReference());
    assertThat(second.getStatus()).isEqualTo("SUBMITTED");
    verify(store, times(2)).claimCancellation(any(), anyString(), any(), any());
  }

  @Test
  @DisplayName("Fallo al guardar CDR")
  void whenCdrSaveFailsThenRecoveryConsultsWithoutNewNumber() {
    // SUNAT pudo recibir el documento aunque el guardado de su constancia haya fallado.
    var document = issue("receipts/cash/request.json", "ticket-save-recovery");
    document.setStatus("UNKNOWN");
    doReturn(new pe.invoicing.app.service.document.models.SunatStatus("0", java.util.Optional.of(
            fixture("sunat/accepted/response.json", pe.invoicing.app.service.document.models.SunatSendResult.class))))
            .when(gateway).getSummaryStatus(anyString(), anyString(), anyString());
    doThrow(new IllegalStateException("Guardado no disponible")).when(store)
            .saveWithArtifact(eq(ACCOUNT), any(), eq("CDR"), any());
    assertThatThrownBy(() -> documents.recoverDocument(COMPANY, document.getId(), null))
            .isInstanceOf(IllegalStateException.class);
    document.setStatus("UNKNOWN");
    doAnswer(c -> c.getArgument(1)).when(store).saveWithArtifact(eq(ACCOUNT), any(), eq("CDR"), any());
    assertThat(documents.recoverDocument(COMPANY, document.getId(), null).getStatus()).isEqualTo("ACCEPTED");
    verify(gateway, times(1)).sendSummary(anyString(), anyString(), any());
  }
}
