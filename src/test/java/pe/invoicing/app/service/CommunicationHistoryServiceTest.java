package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CommunicationHistoryServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("Descargar RC histórico")
  void whenHistoricalReferenceRequestedThenReturnItsOwnBytes() {
    // Una rectificación nueva no cambia el archivo identificado por la referencia anterior.
    var document = issue("receipts/cash/request.json", "historical-summary");
    String reference = document.getCommunication().getBatchReference();
    byte[] bytes = files.get(document.getId() + ":SUMMARY_XML");
    files.put(document.getId() + ":SUMMARY_XML:" + reference, bytes);
    assertThat(documents.downloadCommunicationFile(COMPANY, document.getId(), reference, "SUMMARY_XML").content()).isEqualTo(bytes);
    assertThatThrownBy(() -> documents.downloadCommunicationFile(COMPANY, document.getId(), "../other", "SUMMARY_XML"))
            .isInstanceOf(ResponseStatusException.class);
    verify(gateway, times(1)).sendSummary(anyString(), anyString(), any());
  }
}
