package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.invoicing.app.service.document.models.*;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PendingSaleServiceTest extends AbstractServiceTest {
  @ParameterizedTest @ValueSource(strings={"invoices/cash/request.json","receipts/cash/request.json"})
  @DisplayName("Pendiente sobrevive lectura persistente sin firma ni envío")
  void heldSurvivesRestart(String fixture) {
    var result = documents.savePendingDocument(COMPANY,"held-sale-key",draft(fixture));
    var document = saved.get(result.getDocumentId());
    assertThat(document.getStatus()).isEqualTo("HELD");
    saved.put(document.getId(), JSON.readValue(JSON.writeValueAsString(document), DocumentData.class));
    assertThat(ReconciliationState.pending(saved.get(document.getId()))).isFalse();
    assertThat(documents.recoverDocument(COMPANY,document.getId(),null).getStatus()).isEqualTo("HELD");
    verify(gateway,never()).signedXml(any(),any());
    verify(gateway,never()).sendBill(any(),any());
    verify(gateway,never()).sendSummary(any(),any(),any());
  }

  @Test @DisplayName("Repetir guardar pendiente devuelve la misma reserva")
  void idempotentHeldSale() {
    var draft = draft("receipts/cash/request.json");
    var first = documents.savePendingDocument(COMPANY,"held-sale-key",draft);
    when(artifacts.findIssueOperation(ACCOUNT,COMPANY,crypto.sha256("held-sale-key"))).thenReturn(Optional.of(
            new IssueOperation(crypto.sha256(JSON.valueToTree(draft).toString()),first.getDocumentId())));
    assertThat(documents.savePendingDocument(COMPANY,"held-sale-key",draft).getDocumentId()).isEqualTo(first.getDocumentId());
    assertThatThrownBy(() -> documents.savePendingDocument(COMPANY,"held-sale-key",draft.toBuilder().currency("USD").build()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    verify(store,times(1)).createIssuance(any(),any(),any(),any());
  }
}
