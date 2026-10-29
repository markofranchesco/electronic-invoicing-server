package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.invoicing.app.service.document.models.SunatSendResult;
import pe.invoicing.app.service.document.models.SunatStatus;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

class FiscalEvidenceServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("Distinguir estimación de recepción")
  void whenAcceptanceRecoveredThenKeepEstimateSeparateFromKnownReceiptDate() {
    // El primer envío conserva un límite prudente, pero no prueba la recepción histórica de la CDR.
    var document = issue("invoices/cash/request.json", "evidence-recovered");
    document.setStatus("UNKNOWN");
    document.setTaxResponse(null);
    var first = OffsetDateTime.now(CLOCK).minusDays(2);
    document.getCommunication().setFirstAttemptAt(first);
    doReturn(new SunatStatus("0", Optional.of(fixture("sunat/accepted/response.json", SunatSendResult.class))))
            .when(gateway).consultBill(any());
    documents.getDocument(COMPANY, document.getId());
    assertThat(document.getTaxResponse().getAcceptedAt()).isNull();
    assertThat(document.getTaxResponse().getAcceptanceDeadlineBase()).isEqualTo(first);
    assertThat(document.getTaxResponse().getAcceptanceDateSource()).isEqualTo("FIRST_ATTEMPT_ESTIMATE");
  }
}
